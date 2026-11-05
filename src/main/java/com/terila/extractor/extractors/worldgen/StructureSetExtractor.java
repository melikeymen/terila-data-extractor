package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Writes {@code worldgen/structure_sets.json}: spacing, separation and salt of structure sets. */
public final class StructureSetExtractor extends CodecRegistryDumpExtractor<StructureSet> {

    public StructureSetExtractor() {
        super("structure_sets", DataType.WORLDGEN, "worldgen/structure_sets.json",
                Registries.STRUCTURE_SET);
    }
}
