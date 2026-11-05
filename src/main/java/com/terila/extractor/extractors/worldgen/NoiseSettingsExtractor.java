package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Writes {@code worldgen/noise_settings.json}: sea level, aquifer/ore vein flags, noise router. */
public final class NoiseSettingsExtractor extends CodecRegistryDumpExtractor<NoiseGeneratorSettings> {

    public NoiseSettingsExtractor() {
        super("noise_settings", DataType.WORLDGEN, "worldgen/noise_settings.json",
                Registries.NOISE_SETTINGS);
    }
}
