package com.terila.extractor.extractors.worldgen;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Writes {@code worldgen/structures.json}: villages, temples, strongholds, ruined portals, ... */
public final class StructureExtractor extends CodecRegistryDumpExtractor<Structure> {

    public StructureExtractor() {
        super("structures", DataType.WORLDGEN, "worldgen/structures.json", Registries.STRUCTURE);
    }
}
