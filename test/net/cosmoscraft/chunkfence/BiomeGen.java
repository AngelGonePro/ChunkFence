package net.cosmoscraft.chunkfence;

import java.nio.file.Path;

/** Writes the biome data pack for a version from an Iris pack folder (test helper). */
public final class BiomeGen {
    public static void main(String[] a) throws Exception {
        var defs = BiomeSync.readIrisBiomes(Path.of(a[1]));
        var out = BiomeSync.write(Path.of(a[2]), a[0], defs, System.out::println);
        System.out.println(a[0] + ": " + out.biomes() + " biomes written, changed=" + out.changed());
    }
}
