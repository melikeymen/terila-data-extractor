package com.terila.extractor.extractors.loot;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.datapack.DatapackJsonExtractor;

/**
 * Writes {@code loot/minecraft.json}: every loot table (blocks, entities, chests, gameplay) with its
 * pools, entries, functions and conditions, read from {@code data/minecraft/loot_table/}.
 */
public final class LootTableExtractor extends DatapackJsonExtractor {

    public LootTableExtractor() {
        super("loot_tables", DataType.LOOT, "loot/minecraft.json", "loot_table",
                "loot/minecraft.nbt");
    }
}
