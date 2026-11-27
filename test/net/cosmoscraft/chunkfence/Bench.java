package net.cosmoscraft.chunkfence;

import java.nio.file.Path;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Bench {
    public static void main(String[] args) throws Exception {
        final Path dir = Path.of(args[0]);
        final int threads = Integer.parseInt(args[1]);

        long t = System.nanoTime();
        final ChunkSet headers = RegionScanner.scanHeaders(dir);
        System.out.printf("header pass : %d chunks, %d regions in %d ms%n", headers.chunkCount(), headers.regionCount(), (System.nanoTime() - t) / 1_000_000);

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final RegionScanner.Stats stats = new RegionScanner.Stats();
        t = System.nanoTime();
        final ChunkSet deep = RegionScanner.scanDeep(dir, new RegionScanner.Options(true, true), pool, stats);
        final long ms = (System.nanoTime() - t) / 1_000_000;
        pool.shutdown();
        System.out.printf("deep pass   : %d chunks kept in %d ms with %d threads (%.0f chunks/s)  %s%n", deep.chunkCount(), ms, threads, deep.chunkCount() * 1000.0 / ms, stats);

        // lookup cost: random chunks around the indexed area, half hits half misses
        final SplittableRandom rnd = new SplittableRandom(1);
        final int n = 1 << 20;
        final int[] xs = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = rnd.nextInt(-64, 64 * 32);
            zs[i] = rnd.nextInt(-64, 64 * 32);
        }
        long hits = 0;
        for (int round = 0; round < 30; round++) {
            t = System.nanoTime();
            for (int i = 0; i < n; i++) {
                if (deep.contains(xs[i], zs[i])) hits++;
            }
            if (round == 29) {
                System.out.printf("contains()  : %.1f ns per lookup (hits=%d)%n", (System.nanoTime() - t) / (double) n, hits);
            }
        }
        System.out.printf("memory      : ~%d KiB for the set (%d regions x 128 B + table)%n", (deep.regionCount() * (128 + 16) + 32 * 16 * 2) / 1024, deep.regionCount());
    }
}
