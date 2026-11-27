package net.cosmoscraft.chunkfence;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Reads Anvil region files (.mca) directly from disk to find out which chunks exist.
 * The server is never asked to load anything, so scanning cannot generate chunks.
 */
public final class RegionScanner {

    private static final Pattern REGION_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final int SECTOR = 4096;

    private RegionScanner() {
    }

    /**
     * @param requireFull only keep chunks whose generation status is "full"; partially generated
     *                    edge chunks (proto chunks) are excluded
     * @param ignoreEmpty exclude chunks whose sections contain nothing but air (void chunks)
     */
    public record Options(boolean requireFull, boolean ignoreEmpty) {
        boolean needsDeepScan() {
            return this.requireFull || this.ignoreEmpty;
        }
    }

    public static final class Stats {
        public final AtomicLong regionFiles = new AtomicLong();
        public final AtomicLong present = new AtomicLong();
        public final AtomicLong kept = new AtomicLong();
        public final AtomicLong notFull = new AtomicLong();
        public final AtomicLong empty = new AtomicLong();
        public final AtomicLong unreadable = new AtomicLong();
        public final AtomicLong uninspectable = new AtomicLong();
        public final AtomicLong regionsDone = new AtomicLong();

        @Override
        public String toString() {
            return "regionFiles=" + this.regionFiles + " present=" + this.present + " kept=" + this.kept
                + " notFull=" + this.notFull + " empty=" + this.empty + " unreadable=" + this.unreadable
                + " uninspectable=" + this.uninspectable;
        }
    }

    record RegionFile(Path path, int rx, int rz) {
    }

    static List<RegionFile> listRegions(Path dir) throws IOException {
        final List<RegionFile> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "r.*.mca")) {
            for (Path p : ds) {
                final Matcher m = REGION_NAME.matcher(p.getFileName().toString());
                if (m.matches() && Files.isRegularFile(p)) {
                    out.add(new RegionFile(p, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                }
            }
        }
        return out;
    }

    /** Fast pass: a chunk counts if the region header has an entry for it. Reads 4 KiB per region file. */
    public static ChunkSet scanHeaders(Path dir) throws IOException {
        final List<RegionFile> regions = listRegions(dir);
        final ChunkSet set = new ChunkSet(regions.size());
        final ByteBuffer header = ByteBuffer.allocate(SECTOR);
        final long[] bits = new long[16];
        for (RegionFile rf : regions) {
            try (FileChannel ch = FileChannel.open(rf.path(), StandardOpenOption.READ)) {
                if (!readHeader(ch, header)) {
                    continue;
                }
                java.util.Arrays.fill(bits, 0L);
                boolean any = false;
                for (int i = 0; i < 1024; i++) {
                    final int entry = header.getInt(i * 4);
                    if (entry != 0 && (entry >>> 8) >= 2) {
                        bits[i >>> 6] |= 1L << i;
                        any = true;
                    }
                }
                if (any) {
                    set.addRegion(rf.rx(), rf.rz(), bits);
                }
            } catch (IOException ignored) {
                // unreadable region file: its chunks stay outside the fence
            }
        }
        return set;
    }

    private static boolean readHeader(FileChannel ch, ByteBuffer header) throws IOException {
        header.clear();
        while (header.hasRemaining()) {
            if (ch.read(header, header.position()) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Full pass: decompresses every chunk and applies the options. Runs region files in parallel on {@code pool}. */
    public static ChunkSet scanDeep(Path dir, Options options, ExecutorService pool, Stats stats)
        throws IOException, InterruptedException {
        final List<RegionFile> regions = listRegions(dir);
        stats.regionFiles.set(regions.size());
        final List<Future<long[]>> futures = new ArrayList<>(regions.size());
        for (RegionFile rf : regions) {
            futures.add(pool.submit(() -> {
                try {
                    return scanRegion(rf.path(), options, stats);
                } finally {
                    stats.regionsDone.incrementAndGet();
                }
            }));
        }
        final ChunkSet set = new ChunkSet(regions.size());
        for (int i = 0; i < regions.size(); i++) {
            try {
                final long[] bits = futures.get(i).get();
                if (bits != null) {
                    set.addRegion(regions.get(i).rx(), regions.get(i).rz(), bits);
                }
            } catch (ExecutionException e) {
                // region could not be opened at all: its chunks stay outside the fence
            } catch (InterruptedException e) {
                for (Future<long[]> f : futures) {
                    f.cancel(true);
                }
                throw e;
            }
        }
        return set;
    }

    static long[] scanRegion(Path file, Options options, Stats stats) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final ByteBuffer header = ByteBuffer.allocate(SECTOR);
            if (!readHeader(ch, header)) {
                return null;
            }
            final long fileSize = ch.size();
            final long[] bits = new long[16];
            boolean any = false;
            final Inflater inflater = new Inflater();
            ByteBuffer data = ByteBuffer.allocate(64 * 1024);
            try {
                for (int i = 0; i < 1024; i++) {
                    final int entry = header.getInt(i * 4);
                    final int offset = entry >>> 8;
                    final int sectors = entry & 0xFF;
                    if (entry == 0 || offset < 2) {
                        continue;
                    }
                    stats.present.incrementAndGet();
                    if (!options.needsDeepScan()) {
                        bits[i >>> 6] |= 1L << i;
                        any = true;
                        stats.kept.incrementAndGet();
                        continue;
                    }
                    Verdict v;
                    try {
                        data = readChunkBytes(ch, file, i, offset, sectors, fileSize, data);
                        v = inspect(data, inflater, options);
                    } catch (IOException | RuntimeException e) {
                        v = Verdict.UNREADABLE;
                    }
                    switch (v) {
                        case NOT_FULL -> stats.notFull.incrementAndGet();
                        case EMPTY -> stats.empty.incrementAndGet();
                        default -> {
                            if (v == Verdict.UNREADABLE) {
                                stats.unreadable.incrementAndGet();
                            } else if (v == Verdict.UNINSPECTABLE) {
                                stats.uninspectable.incrementAndGet();
                            }
                            // KEEP, UNREADABLE and UNINSPECTABLE are all allowed: never fence off
                            // a chunk that exists just because it could not be decoded here.
                            bits[i >>> 6] |= 1L << i;
                            any = true;
                            stats.kept.incrementAndGet();
                        }
                    }
                }
            } finally {
                inflater.end();
            }
            return any ? bits : null;
        }
    }

    enum Verdict { KEEP, NOT_FULL, EMPTY, UNREADABLE, UNINSPECTABLE }

    /** Returns a buffer holding [compression byte][payload], or for external chunks the .mcc contents prefixed by the type. */
    private static ByteBuffer readChunkBytes(FileChannel ch, Path file, int index, int offset, int sectors,
                                             long fileSize, ByteBuffer buf) throws IOException {
        final long pos = (long) offset * SECTOR;
        if (pos + 5 > fileSize) {
            throw new IOException("Chunk outside file");
        }
        final ByteBuffer head = ByteBuffer.allocate(5);
        while (head.hasRemaining()) {
            if (ch.read(head, pos + head.position()) < 0) {
                throw new IOException("EOF");
            }
        }
        final int length = head.getInt(0);
        final int type = head.get(4) & 0xFF;
        if ((type & 0x80) != 0) {
            // oversized chunk stored next to the region file as c.<x>.<z>.mcc
            final String name = file.getFileName().toString();
            final String[] parts = name.split("\\.");
            final int rx = Integer.parseInt(parts[1]);
            final int rz = Integer.parseInt(parts[2]);
            final int cx = (rx << 5) + (index & 31);
            final int cz = (rz << 5) + (index >>> 5);
            final Path mcc = file.resolveSibling("c." + cx + "." + cz + ".mcc");
            final byte[] ext = Files.readAllBytes(mcc);
            final ByteBuffer out = ByteBuffer.allocate(ext.length + 1);
            out.put((byte) (type & 0x7F)).put(ext).flip();
            return out;
        }
        if (length <= 1 || length > sectors * SECTOR || pos + 4 + length > fileSize) {
            throw new IOException("Bad chunk length " + length);
        }
        ByteBuffer out = buf;
        if (out.capacity() < length) {
            out = ByteBuffer.allocate(Math.max(length, out.capacity() * 2));
        }
        out.clear().limit(length);
        while (out.hasRemaining()) {
            if (ch.read(out, pos + 4 + out.position()) < 0) {
                throw new IOException("EOF");
            }
        }
        out.flip();
        return out;
    }

    private static Verdict inspect(ByteBuffer data, Inflater inflater, Options options) throws IOException {
        final int type = data.get(0) & 0xFF;
        final InputStream raw = new ByteArrayInputStream(data.array(), 1, data.limit() - 1);
        final InputStream decoded;
        switch (type) {
            case 1 -> decoded = new GZIPInputStream(raw, 8192);
            case 2 -> {
                inflater.reset();
                decoded = new InflaterInputStream(raw, inflater, 8192);
            }
            case 3 -> decoded = raw;
            case 4 -> decoded = new Lz4BlockInputStream(raw);
            default -> {
                return Verdict.UNINSPECTABLE; // custom compression (127) or unknown
            }
        }
        final DataInputStream in = new DataInputStream(new BufferedInputStream(decoded, 16 * 1024));
        final ChunkInspector r = ChunkInspector.inspect(in, options.ignoreEmpty());
        if (options.requireFull() && !isFull(r)) {
            return Verdict.NOT_FULL;
        }
        if (options.ignoreEmpty() && !r.nonAir) {
            return Verdict.EMPTY;
        }
        return Verdict.KEEP;
    }

    static boolean isFull(ChunkInspector r) {
        if (r.status == null) {
            // pre-1.13 chunks have no status; TerrainPopulated=0 means unfinished
            return r.terrainPopulated != 0;
        }
        return isFullStatusName(r.status);
    }

    static boolean isFullStatusName(String status) {
        String s = status;
        final int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        return s.equals("full") || s.equals("fullchunk") || s.equals("postprocessed");
    }
}
