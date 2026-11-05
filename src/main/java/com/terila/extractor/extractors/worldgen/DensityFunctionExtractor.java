package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/** Writes {@code worldgen/density_functions.json}: the terrain density graph. */
public final class DensityFunctionExtractor extends CodecRegistryDumpExtractor<DensityFunction> {

    public DensityFunctionExtractor() {
        super("density_functions", DataType.WORLDGEN, "worldgen/density_functions.json",
                Registries.DENSITY_FUNCTION);
    }
}
