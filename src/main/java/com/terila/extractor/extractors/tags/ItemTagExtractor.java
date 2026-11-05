package com.terila.extractor.extractors.tags;

// Written by AI
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;

/** Writes {@code tags/items.json}, including {@code enchantable/*} and repair material tags. */
public final class ItemTagExtractor extends TagExtractor<Item> {

    public ItemTagExtractor() {
        super("item_tags", "tags/items.json", Registries.ITEM);
    }
}
