package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.DimensionType;

/** Writes {@code worldgen/dimensions.json}: height, coordinate scale, and the dimension flags. */
public final class DimensionTypeExtractor extends CodecRegistryDumpExtractor<DimensionType> {

    public DimensionTypeExtractor() {
        super("dimensions", DataType.WORLDGEN, "worldgen/dimensions.json", Registries.DIMENSION_TYPE);
    }
}
