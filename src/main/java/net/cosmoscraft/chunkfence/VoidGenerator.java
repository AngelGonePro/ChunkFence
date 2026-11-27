package net.cosmoscraft.chunkfence;

import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * Generates nothing: every new chunk is air with the_void biome. Chunks that already exist on disk
 * are loaded as they are. Any world using this generator is automatically treated as a frozen world.
 */
public final class VoidGenerator extends ChunkGenerator {

    private static final VoidBiomes BIOMES = new VoidBiomes();

    // All shouldGenerate*() methods default to false in ChunkGenerator, so no terrain, caves,
    // decorations, mobs or structures are produced.

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return BIOMES;
    }

    static final class VoidBiomes extends BiomeProvider {
        @Override
        public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    }
}
