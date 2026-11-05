package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

/** Writes {@code worldgen/multi_noise.json}: the biome parameter points used by the noise router. */
public final class MultiNoiseExtractor
        extends CodecRegistryDumpExtractor<MultiNoiseBiomeSourceParameterList> {

    public MultiNoiseExtractor() {
        super("multi_noise", DataType.WORLDGEN, "worldgen/multi_noise.json",
                Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
    }
}
