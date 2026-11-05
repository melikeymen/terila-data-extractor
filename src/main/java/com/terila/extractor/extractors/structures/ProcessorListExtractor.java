package com.terila.extractor.extractors.structures;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;

/**
 * Writes {@code worldgen/processor_lists.json}: every structure processor list.
 *
 * <p>Processor lists are the transformations applied while a template is placed — rotating blocks,
 * decaying bricks, replacing the air inside a ruined portal. They are referenced by name from
 * template pools, so a consumer needs them to reproduce a structure faithfully.
 */
public final class ProcessorListExtractor extends CodecRegistryDumpExtractor<StructureProcessorList> {

    public ProcessorListExtractor() {
        super("processor_lists", DataType.WORLDGEN, "worldgen/processor_lists.json",
                Registries.PROCESSOR_LIST);
    }
}
