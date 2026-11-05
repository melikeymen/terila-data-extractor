package com.terila.extractor.extractors.tags;

// Written by AI
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.material.Fluid;

/** Writes {@code tags/fluids.json}. */
public final class FluidTagExtractor extends TagExtractor<Fluid> {

    public FluidTagExtractor() {
        super("fluid_tags", "tags/fluids.json", Registries.FLUID);
    }
}
