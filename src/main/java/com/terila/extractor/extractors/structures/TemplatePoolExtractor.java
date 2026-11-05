package com.terila.extractor.extractors.structures;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * Writes {@code worldgen/template_pools.json}: every structure template pool.
 *
 * <p>A pool is the piece of worldgen that actually points at the NBT templates dumped by
 * {@link StructureTemplateExtractor}: each {@code single} or {@code legacysingle} element names a
 * structure resource and a list of processors. Together the two files describe a structure
 * completely — where its pieces go and what each piece is built from.
 */
public final class TemplatePoolExtractor extends CodecRegistryDumpExtractor<StructureTemplatePool> {

    public TemplatePoolExtractor() {
        super("template_pools", DataType.WORLDGEN, "worldgen/template_pools.json",
                Registries.TEMPLATE_POOL);
    }
}
