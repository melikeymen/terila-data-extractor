package com.terila.extractor.extractors.items;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.items.model.ItemData;

import java.io.IOException;
import java.util.List;

/**
 * Writes {@code items/enchantable.json}: enchantability per item, taken from the
 * {@code minecraft:enchantable} component (the successor of {@code getEnchantmentValue}).
 */
public final class ItemEnchantmentExtractor extends AbstractExtractor {

    public ItemEnchantmentExtractor() {
        super("item_enchantable", DataType.ITEMS, "items/enchantable.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        List<ItemData> enchantable = ItemCatalog.of(context).withComponent("minecraft:enchantable");

        JsonObject values = new JsonObject();
        JsonObject components = new JsonObject();
        for (ItemData item : enchantable) {
            values.addProperty(item.id(), item.enchantability());
            components.add(item.id(), item.components().get("minecraft:enchantable"));
        }

        JsonArray ids = new JsonArray();
        enchantable.forEach(item -> ids.add(item.id()));

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), enchantable.size()));
        root.add("items", ids);
        root.add("enchantability", values);
        root.add("enchantable_components", components);
        write(context, root);
        return enchantable.size();
    }
}
