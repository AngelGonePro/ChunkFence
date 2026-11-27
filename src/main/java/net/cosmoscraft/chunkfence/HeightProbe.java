package net.cosmoscraft.chunkfence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Finds out which height a world's chunks were saved with, by reading a sample of chunks straight from
 * the region files. Vanilla saves every block section of a chunk (air ones included), so the lowest and
 * highest section that has block data gives the world's height at the time the chunk was saved.
 */
final class HeightProbe {

    /** Saved range in section coordinates (block Y = section * 16), plus how many sampled chunks were short. */
    record Result(int minSection, int maxSection, int sampled, int shortChunks) {
        int minY() {
            return this.minSection * 16;
        }

        /** Exclusive top, like World#getMaxHeight(). */
        int maxY() {
            return (this.maxSection + 1) * 16;
        }

        @Override
        public String toString() {
            return "Y " + minY() + " to " + maxY() + " (" + this.sampled + " chunks sampled)";
        }
    }

    private HeightProbe() {
    }

    static Result probe(Path regionDir, int maxChunks) throws IOException {
        final List<RegionScanner.RegionFile> regions = RegionScanner.listRegions(regionDir);
        if (regions.isEmpty()) {
            return null;
        }
        final int perRegion = Math.max(1, Math.min(32, maxChunks / regions.size()));
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int sampled = 0;
        final int[][] ranges = new int[Math.max(maxChunks, regions.size()) + 64][];
        for (RegionScanner.RegionFile rf : regions) {
            if (sampled >= maxChunks && sampled >= regions.size()) {
                break;
            }
            try (FileChannel ch = FileChannel.open(rf.path(), StandardOpenOption.READ)) {
                final long size = ch.size();
                if (size < 8192) {
                    continue;
                }
                final ByteBuffer header = ByteBuffer.allocate(RegionIO.SECTOR);
                while (header.hasRemaining() && ch.read(header, header.position()) >= 0) {
                    // read the whole location table
                }
                int present = 0;
                for (int i = 0; i < 1024; i++) {
                    if (header.getInt(i * 4) != 0) {
                        present++;
                    }
                }
                if (present == 0) {
                    continue;
                }
                final int step = Math.max(1, present / perRegion);
                int seen = 0;
                int taken = 0;
                for (int i = 0; i < 1024 && taken < perRegion; i++) {
                    final int entry = header.getInt(i * 4);
                    if (entry == 0 || (entry >>> 8) < 2) {
                        continue;
                    }
                    if (seen++ % step != 0) {
                        continue;
                    }
                    final int[] r = sampleChunk(ch, size, rf.path(), entry, i);
                    if (r == null) {
                        continue;
                    }
                    taken++;
                    if (sampled < ranges.length) {
                        ranges[sampled] = r;
                    }
                    sampled++;
                    min = Math.min(min, r[0]);
                    max = Math.max(max, r[1]);
                }
            } catch (IOException ignored) {
                // unreadable region file: skip
            }
        }
        if (sampled == 0) {
            return null;
        }
        int shortChunks = 0;
        for (int i = 0; i < Math.min(sampled, ranges.length); i++) {
            if (ranges[i][0] > min || ranges[i][1] < max) {
                shortChunks++;
            }
        }
        return new Result(min, max, sampled, shortChunks);
    }

    private static int[] sampleChunk(FileChannel ch, long fileSize, Path file, int entry, int index) {
        try {
            final long pos = (long) (entry >>> 8) * RegionIO.SECTOR;
            final int sectors = entry & 0xFF;
            final long want = Math.min((long) Math.max(sectors, 1) * RegionIO.SECTOR, fileSize - pos);
            if (want < 5) {
                return null;
            }
            final ByteBuffer buf = ByteBuffer.allocate((int) want);
            while (buf.hasRemaining() && ch.read(buf, pos + buf.position()) >= 0) {
                // fill
            }
            // Re-base so RegionIO can treat it like a file whose chunk starts at sector 0.
            final byte[] bytes = buf.array();
            final byte[] fake = new byte[bytes.length];
            System.arraycopy(bytes, 0, fake, 0, bytes.length);
            final Nbt.Root root = RegionIO.readChunkAt(file, fake, 0, index);
            final Nbt.Compound c = root.compound();
            final String status = c.getString("Status");
            if (status != null && !RegionScanner.isFullStatusName(status)) {
                return null; // proto chunks may not have all sections yet
            }
            return RegionIO.blockSectionRange(c);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
