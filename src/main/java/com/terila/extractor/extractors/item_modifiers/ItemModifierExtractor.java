package com.terila.extractor.extractors.item_modifiers;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.datapack.DatapackJsonExtractor;
import com.terila.extractor.extractors.registry.RegistryVocabulary;

/**
 * Writes {@code item_modifiers/minecraft.json}: every item modifier chain, read from
 * {@code data/minecraft/item_modifier/}.
 *
 * <p>Vanilla ships no item modifiers of its own — the directory exists so data packs can add them —
 * so this document is normally empty. The accompanying {@code type_vocabulary} therefore carries the
 * real value: the 42 loot function types an item modifier may be built from (an item modifier is
 * exactly a list of loot item functions), which is the whole grammar of the format.
 */
public final class ItemModifierExtractor extends DatapackJsonExtractor {

    public ItemModifierExtractor() {
        super("item_modifiers", DataType.ITEM_MODIFIERS, "item_modifiers/minecraft.json",
                "item_modifier");
    }

    @Override
    protected void decorate(ExtractorContext context, JsonObject root) {
        RegistryVocabulary.describe(context, root,
                "minecraft:loot_function_type",
                "minecraft:loot_nbt_provider_type",
                "minecraft:loot_score_provider_type",
                "minecraft:loot_pool_entry_type");
    }
}
