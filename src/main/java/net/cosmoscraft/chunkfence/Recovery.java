package net.cosmoscraft.chunkfence;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Restores block sections that were cut off when a world was loaded with a smaller height than its chunks
 * were saved with.
 * <p>
 * When the server saves a chunk it always writes it to newly allocated sectors and only then frees the old
 * ones, so the previous (full-height) copy usually stays in the region file until some later save happens to
 * reuse those sectors. This class finds such leftover copies and copies back the sections (and block
 * entities) that the current copy is missing. Everything inside the current copy's height range is kept as it
 * is now, so builds made since then are preserved.
 */
final class Recovery {

    record Summary(int regionFiles, long chunks, long damaged, long repaired, long noCopyLeft, List<String> unrecovered,
                   List<String> changedFiles) {
    }

    private record Candidate(Nbt.Root root, int[] range, long lastUpdate) {
    }

    private Recovery() {
    }

    /**
     * @param repair     false = only report
     * @param backupDir  where original region files are copied before being replaced (repair only)
     */
    static Summary run(Path regionDir, boolean repair, Path backupDir, ExecutorService pool, Consumer<String> log)
        throws IOException, InterruptedException {
        final List<RegionScanner.RegionFile> regions = RegionScanner.listRegions(regionDir);
        // Pass 1: block-section range of every chunk currently referenced by the region headers.
        final List<Future<int[][]>> pass1 = new ArrayList<>();
        for (RegionScanner.RegionFile rf : regions) {
            pass1.add(pool.submit(() -> currentRanges(rf.path())));
        }
        final int[][][] ranges = new int[regions.size()][][];
        int gMin = Integer.MAX_VALUE;
        int gMax = Integer.MIN_VALUE;
        long chunks = 0;
        int lastPct = 0;
        for (int r = 0; r < regions.size(); r++) {
            final int pct = regions.size() >= 50 ? (r * 100 / regions.size()) / 10 * 10 : 0;
            if (pct > lastPct) {
                lastPct = pct;
                log.accept("  ... " + pct + "% of " + regions.size() + " region files checked");
            }
            try {
                ranges[r] = pass1.get(r).get();
            } catch (java.util.concurrent.ExecutionException e) {
                ranges[r] = null;
            }
            if (ranges[r] == null) {
                continue;
            }
            for (int[] range : ranges[r]) {
                if (range != null) {
                    chunks++;
                    gMin = Math.min(gMin, range[0]);
                    gMax = Math.max(gMax, range[1]);
                }
            }
        }
        if (gMin == Integer.MAX_VALUE) {
            return new Summary(regions.size(), 0, 0, 0, 0, List.of(), List.of());
        }
        final int fMin = gMin;
        final int fMax = gMax;
        // Pass 2: regions that contain chunks shorter than the tallest saved height.
        final List<Future<RegionResult>> pass2 = new ArrayList<>();
        for (int r = 0; r < regions.size(); r++) {
            final int[][] rr = ranges[r];
            if (rr == null) {
                continue;
            }
            boolean damaged = false;
            for (int[] range : rr) {
                if (range != null && (range[0] > fMin || range[1] < fMax)) {
                    damaged = true;
                    break;
                }
            }
            if (damaged) {
                final RegionScanner.RegionFile rf = regions.get(r);
                pass2.add(pool.submit(() -> repairRegion(rf.path(), rr, fMin, fMax, repair, backupDir)));
            }
        }
        long damagedCount = 0;
        long repaired = 0;
        long noCopy = 0;
        final List<String> unrecovered = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        for (Future<RegionResult> f : pass2) {
            try {
                final RegionResult res = f.get();
                damagedCount += res.damaged;
                repaired += res.repaired;
                noCopy += res.unrecovered.size();
                unrecovered.addAll(res.unrecovered);
                if (res.changedFile != null) {
                    changed.add(res.changedFile);
                }
            } catch (java.util.concurrent.ExecutionException e) {
                final StringWriter sw = new StringWriter();
                e.getCause().printStackTrace(new PrintWriter(sw));
                log.accept("Recovery failed for one region file (left untouched): " + sw);
            }
        }
        return new Summary(regions.size(), chunks, damagedCount, repaired, noCopy, unrecovered, changed);
    }

    /** Block-section range per header slot (null = empty or unreadable). */
    static int[][] currentRanges(Path file) throws IOException {
        final byte[] data = Files.readAllBytes(file);
        if (data.length < 8192) {
            return null;
        }
        final int[][] out = new int[1024][];
        for (int i = 0; i < 1024; i++) {
            try {
                final Nbt.Root root = RegionIO.readChunk(file, data, i);
                if (root == null) {
                    continue;
                }
                final String status = statusOf(root.compound());
                if (status != null && !RegionScanner.isFullStatusName(status)) {
                    continue; // unfinished edge chunks are not compared
                }
                out[i] = RegionIO.blockSectionRange(root.compound());
            } catch (IOException | RuntimeException e) {
                out[i] = null;
            }
        }
        return out;
    }

    private static String statusOf(Nbt.Compound c) {
        final String s = c.getString("Status");
        if (s != null) {
            return s;
        }
        final Nbt.Compound level = c.getCompound("Level");
        return level == null ? null : level.getString("Status");
    }

    private static final class RegionResult {
        int damaged;
        int repaired;
        final List<String> unrecovered = new ArrayList<>();
        String changedFile;
    }

    private static RegionResult repairRegion(Path file, int[][] ranges, int gMin, int gMax, boolean repair, Path backupDir)
        throws IOException {
        final RegionResult res = new RegionResult();
        final byte[] data = Files.readAllBytes(file);
        final int rx = RegionIO.rx(file);
        final int rz = RegionIO.rz(file);
        final int sectors = data.length / RegionIO.SECTOR;

        // sectors used by the current copies
        final boolean[] used = new boolean[Math.max(sectors, 2)];
        used[0] = true;
        if (used.length > 1) {
            used[1] = true;
        }
        final boolean[] damagedSlot = new boolean[1024];
        for (int i = 0; i < 1024; i++) {
            final int entry = RegionIO.headerEntry(data, i);
            if (entry == 0) {
                continue;
            }
            final int off = entry >>> 8;
            final int cnt = entry & 0xFF;
            for (int s = off; s < off + cnt && s < used.length; s++) {
                used[s] = true;
            }
            final int[] r = ranges[i];
            if (r != null && (r[0] > gMin || r[1] < gMax)) {
                damagedSlot[i] = true;
                res.damaged++;
            }
        }

        // leftover copies in free sectors
        final Map<Integer, Candidate> best = new HashMap<>();
        for (int s = 2; s < sectors; s++) {
            if (used[s]) {
                continue;
            }
            final int p = s * RegionIO.SECTOR;
            final int length = (data[p] & 0xFF) << 24 | (data[p + 1] & 0xFF) << 16 | (data[p + 2] & 0xFF) << 8 | (data[p + 3] & 0xFF);
            final int type = data[p + 4] & 0xFF;
            if (length <= 1 || type < 1 || type > 4 || (long) p + 4 + length > data.length) {
                continue;
            }
            final Nbt.Root root;
            try {
                root = RegionIO.readChunkAt(file, data, s, -1);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            final Nbt.Compound c = root.compound();
            final Nbt.Compound lvl = c.getCompound("Level") != null ? c.getCompound("Level") : c;
            final Integer x = lvl.getInt("xPos");
            final Integer z = lvl.getInt("zPos");
            if (x == null || z == null || (x >> 5) != rx || (z >> 5) != rz) {
                continue;
            }
            final int slot = ((z & 31) << 5) | (x & 31);
            if (!damagedSlot[slot]) {
                continue;
            }
            final int[] range = RegionIO.blockSectionRange(c);
            final int[] cur = ranges[slot];
            if (range == null || !(range[0] < cur[0] || range[1] > cur[1])) {
                continue; // not taller than what is there now
            }
            final Long lu = lvl.getLong("LastUpdate");
            final Candidate cand = new Candidate(root, range, lu == null ? Long.MIN_VALUE : lu);
            final Candidate prev = best.get(slot);
            if (prev == null || coverage(cand.range) > coverage(prev.range)
                || (coverage(cand.range) == coverage(prev.range) && cand.lastUpdate > prev.lastUpdate)) {
                best.put(slot, cand);
            }
        }

        final byte[][] payloads = new byte[1024][];
        final int[] types = new int[1024];
        final int[] timestamps = new int[1024];
        boolean changedAny = false;
        for (int i = 0; i < 1024; i++) {
            final int entry = RegionIO.headerEntry(data, i);
            if (entry == 0) {
                continue;
            }
            timestamps[i] = RegionIO.timestamp(data, i);
            final int cx = (rx << 5) + (i & 31);
            final int cz = (rz << 5) + (i >>> 5);
            if (damagedSlot[i]) {
                final Candidate cand = best.get(i);
                if (cand == null) {
                    res.unrecovered.add("chunk " + cx + "," + cz + " (blocks " + (cx << 4) + "," + (cz << 4) + ")");
                } else {
                    try {
                        final Nbt.Root current = RegionIO.readChunk(file, data, i);
                        merge(current.compound(), cand.root.compound(), ranges[i]);
                        payloads[i] = RegionIO.encodeZlib(current);
                        types[i] = 2;
                        res.repaired++;
                        changedAny = true;
                        continue;
                    } catch (IOException | RuntimeException e) {
                        res.unrecovered.add("chunk " + cx + "," + cz + " (merge failed: " + e.getMessage() + ")");
                    }
                }
            }
            // unchanged: copy the stored bytes as they are
            final int off = (entry >>> 8) * RegionIO.SECTOR;
            if ((entry >>> 8) < 2 || off + 5 > data.length) {
                continue;
            }
            final int length = (data[off] & 0xFF) << 24 | (data[off + 1] & 0xFF) << 16 | (data[off + 2] & 0xFF) << 8 | (data[off + 3] & 0xFF);
            final int type = data[off + 4] & 0xFF;
            if ((type & 0x80) != 0) {
                final Path mcc = file.resolveSibling("c." + cx + "." + cz + ".mcc");
                if (Files.exists(mcc)) {
                    payloads[i] = Files.readAllBytes(mcc);
                    types[i] = type & 0x7F;
                }
                continue;
            }
            if (length <= 1 || off + 4L + length > data.length) {
                continue; // already broken: dropping it is what the server would do too
            }
            payloads[i] = java.util.Arrays.copyOfRange(data, off + 5, off + 4 + length);
            types[i] = type;
        }

        if (repair && changedAny) {
            final Path backup = backupDir.resolve(file.getFileName());
            Files.createDirectories(backupDir);
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            RegionIO.writeRegion(file, payloads, types, timestamps);
            res.changedFile = file.toString();
        }
        return res;
    }

    private static int coverage(int[] r) {
        return r[1] - r[0];
    }

    /** Copies sections, block entities and scheduled ticks outside the current height range from {@code old}. */
    static void merge(Nbt.Compound current, Nbt.Compound old, int[] curRange) {
        final boolean levelFormat = current.getCompound("Level") != null;
        final Nbt.Compound cur = levelFormat ? current.getCompound("Level") : current;
        final Nbt.Compound src = old.getCompound("Level") != null ? old.getCompound("Level") : old;
        final String secKey = cur.getList("sections") != null ? "sections" : "Sections";
        Nbt.NbtList curSections = cur.getList(secKey);
        final Nbt.NbtList oldSections = src.getList(secKey);
        if (oldSections == null) {
            return;
        }
        if (curSections == null) {
            curSections = new Nbt.NbtList(Nbt.COMPOUND);
            cur.put(secKey, new Nbt.Tag(Nbt.LIST, curSections));
        }
        // sections from the old copy that lie outside the current block range
        final List<Nbt.Tag> restored = new ArrayList<>();
        for (Nbt.Tag t : oldSections.items) {
            final Integer y = t.compound().getInt("Y");
            if (y != null && (y < curRange[0] || y > curRange[1]) && hasBlocks(t.compound())) {
                final Nbt.Compound copy = t.compound();
                copy.map.remove("BlockLight"); // light is recalculated by the server
                copy.map.remove("SkyLight");
                restored.add(t);
            }
        }
        if (restored.isEmpty()) {
            return;
        }
        // drop light-only padding sections of the current copy that sit where restored sections go
        final java.util.Set<Integer> restoredY = new java.util.HashSet<>();
        for (Nbt.Tag t : restored) {
            restoredY.add(t.compound().getInt("Y"));
        }
        curSections.items.removeIf(t -> t.type() == Nbt.COMPOUND && restoredY.contains(t.compound().getInt("Y")));
        // also drop the remaining light-only padding sections; the server rebuilds light anyway
        curSections.items.removeIf(t -> t.type() == Nbt.COMPOUND && !hasBlocks(t.compound()));
        for (Nbt.Tag t : curSections.items) {
            t.compound().map.remove("BlockLight");
            t.compound().map.remove("SkyLight");
        }
        curSections.items.addAll(restored);
        curSections.items.sort((a, b) -> Integer.compare(a.compound().getInt("Y"), b.compound().getInt("Y")));

        final int lowY = curRange[0] * 16;
        final int highY = curRange[1] * 16 + 15;
        mergeByPosition(cur, src, levelFormat ? "TileEntities" : "block_entities", lowY, highY);
        mergeByPosition(cur, src, "block_ticks", lowY, highY);
        mergeByPosition(cur, src, "fluid_ticks", lowY, highY);

        // make the server rebuild light and heightmaps for the now taller chunk
        cur.put("isLightOn", new Nbt.Tag(Nbt.BYTE, (byte) 0));
        cur.map.remove("Heightmaps");
    }

    private static boolean hasBlocks(Nbt.Compound section) {
        return section.get("block_states") != null || section.get("Palette") != null || section.get("Blocks") != null;
    }

    private static void mergeByPosition(Nbt.Compound cur, Nbt.Compound src, String key, int lowY, int highY) {
        final Nbt.NbtList from = src.getList(key);
        if (from == null || from.items.isEmpty()) {
            return;
        }
        Nbt.NbtList to = cur.getList(key);
        if (to == null) {
            to = new Nbt.NbtList(Nbt.COMPOUND);
            cur.put(key, new Nbt.Tag(Nbt.LIST, to));
        }
        if (to.elementType == Nbt.END) {
            to.elementType = Nbt.COMPOUND;
        }
        final java.util.Set<String> have = new java.util.HashSet<>();
        for (Nbt.Tag t : to.items) {
            if (t.type() == Nbt.COMPOUND) {
                have.add(pos(t.compound()));
            }
        }
        for (Nbt.Tag t : from.items) {
            if (t.type() != Nbt.COMPOUND) {
                continue;
            }
            final Integer y = t.compound().getInt("y");
            if (y == null || (y >= lowY && y <= highY)) {
                continue;
            }
            if (have.add(pos(t.compound()))) {
                to.items.add(t);
            }
        }
    }

    private static String pos(Nbt.Compound c) {
        return c.getInt("x") + "," + c.getInt("y") + "," + c.getInt("z");
    }
}
