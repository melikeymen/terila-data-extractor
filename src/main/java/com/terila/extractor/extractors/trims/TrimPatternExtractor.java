package com.terila.extractor.extractors.trims;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.equipment.trim.TrimPattern;

/** Writes {@code trims/patterns.json}: asset id, description, template item and decal flag. */
public final class TrimPatternExtractor extends CodecRegistryDumpExtractor<TrimPattern> {

    public TrimPatternExtractor() {
        super("trim_patterns", DataType.TRIMS, "trims/patterns.json", Registries.TRIM_PATTERN);
    }
}
