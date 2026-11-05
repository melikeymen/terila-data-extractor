package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;

/**
 * Writes {@code worldgen/biomes.json}: temperature, downfall, precipitation, effects and the
 * feature/spawner lists of every biome, including the 26.2 {@code minecraft:sulfur_caves}.
 */
public final class BiomeExtractor extends CodecRegistryDumpExtractor<Biome> {

    public BiomeExtractor() {
        super("biomes", DataType.WORLDGEN, "worldgen/biomes.json", Registries.BIOME);
    }
}
