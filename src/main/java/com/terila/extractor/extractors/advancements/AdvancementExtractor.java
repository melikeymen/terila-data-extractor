package com.terila.extractor.extractors.advancements;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.datapack.DatapackJsonExtractor;

/**
 * Writes {@code advancements/minecraft.json}: parent, criteria, requirements, display and rewards
 * of every advancement, read from {@code data/minecraft/advancement/}.
 */
public final class AdvancementExtractor extends DatapackJsonExtractor {

    public AdvancementExtractor() {
        super("advancements", DataType.ADVANCEMENTS, "advancements/minecraft.json", "advancement",
                "advancements/minecraft.nbt");
    }
}
