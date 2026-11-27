package net.cosmoscraft.chunkfence;

import java.nio.file.Path;

/** Prints the kept chunk indices of one region file for all four option combinations. */
public final class ScanCheck {
    public static void main(String[] args) throws Exception {
        final Path file = Path.of(args[0]);
        for (boolean full : new boolean[]{false, true}) {
            for (boolean empty : new boolean[]{false, true}) {
                final RegionScanner.Stats stats = new RegionScanner.Stats();
                final long[] bits = RegionScanner.scanRegion(file, new RegionScanner.Options(full, empty), stats);
                final StringBuilder sb = new StringBuilder();
                sb.append(full ? 1 : 0).append(empty ? 1 : 0).append(':');
                for (int i = 0; i < 1024; i++) {
                    if (bits != null && (bits[i >>> 6] & (1L << i)) != 0) {
                        sb.append(' ').append(i);
                    }
                }
                System.out.println(sb);
                System.err.println((full ? 1 : 0) + "" + (empty ? 1 : 0) + " " + stats);
            }
        }
    }
}
