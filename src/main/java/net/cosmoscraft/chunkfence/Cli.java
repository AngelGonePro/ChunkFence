package net.cosmoscraft.chunkfence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline use, with the server stopped:
 * <pre>
 *   java -jar ChunkFence.jar heights &lt;region folder&gt;            show the height the saved chunks use
 *   java -jar ChunkFence.jar recover &lt;region folder&gt;            report what can be restored
 *   java -jar ChunkFence.jar recover &lt;region folder&gt; --repair   restore (originals copied to ./chunkfence-backup)
 * </pre>
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("recover") || args[0].equals("heights"))) {
            System.out.println("usage: java -jar ChunkFence.jar heights <region folder>");
            System.out.println("       java -jar ChunkFence.jar recover <region folder> [--repair]");
            System.exit(2);
        }
        final Path dir = Path.of(args[1]);
        if (!Files.isDirectory(dir)) {
            System.out.println("Not a folder: " + dir);
            System.exit(2);
        }
        if (args[0].equals("heights")) {
            final HeightProbe.Result r = HeightProbe.probe(dir, 2000);
            System.out.println(r == null ? "No readable chunks." : "Saved chunks use " + r + ", " + r.shortChunks()
                + " of the sampled chunks are shorter than that.");
            return;
        }
        final boolean repair = args.length > 2 && args[2].equals("--repair");
        final ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        try {
            final Path backup = Path.of("chunkfence-backup").toAbsolutePath();
            final Recovery.Summary s = Recovery.run(dir, repair, backup, pool, System.out::println);
            System.out.println("chunks checked: " + s.chunks());
            System.out.println("cut off: " + s.damaged());
            System.out.println((repair ? "restored: " : "restorable: ") + s.repaired());
            System.out.println("no older full-height copy left: " + s.noCopyLeft());
            s.unrecovered().forEach(u -> System.out.println("  " + u));
            if (repair && !s.changedFiles().isEmpty()) {
                System.out.println("originals copied to " + backup);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
