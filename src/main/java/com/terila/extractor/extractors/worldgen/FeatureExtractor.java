package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Writes {@code worldgen/features.json}: every placed-feature payload with its full configuration.
 *
 * <p>Minecraft 26.3 merged {@code ConfiguredFeature} into {@code Feature}: the
 * {@code minecraft:feature} registry now stores the feature <em>with</em> its config, and
 * {@code PlacedFeature} points straight at it. Entries are therefore dumped through
 * {@link Feature#DIRECT_CODEC} so each one keeps the exact shape a datapack uses.
 */
public final class FeatureExtractor extends CodecRegistryDumpExtractor<Feature> {

    public FeatureExtractor() {
        super("features", DataType.WORLDGEN, "worldgen/features.json", Registries.FEATURE);
    }
}
