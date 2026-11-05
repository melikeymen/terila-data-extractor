package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Writes {@code worldgen/placed_features.json}: which feature is placed where and how densely. */
public final class PlacedFeatureExtractor extends CodecRegistryDumpExtractor<PlacedFeature> {

    public PlacedFeatureExtractor() {
        super("placed_features", DataType.WORLDGEN, "worldgen/placed_features.json",
                Registries.PLACED_FEATURE);
    }
}
