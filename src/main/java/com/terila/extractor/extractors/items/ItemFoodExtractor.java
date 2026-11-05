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
 * Writes {@code items/food.json}: every edible item together with its {@code minecraft:food}
 * component, serialised verbatim so nutrition, saturation and effects stay intact.
 */
public final class ItemFoodExtractor extends AbstractExtractor {

    public ItemFoodExtractor() {
        super("item_food", DataType.ITEMS, "items/food.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        List<ItemData> food = ItemCatalog.of(context).withComponent("minecraft:food");

        JsonObject byItem = new JsonObject();
        for (ItemData item : food) {
            byItem.add(item.id(), item.components().get("minecraft:food"));
        }

        JsonArray ids = new JsonArray();
        food.forEach(item -> ids.add(item.id()));

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), food.size()));
        root.add("items", ids);
        root.add("food_components", byItem);
        write(context, root);
        return food.size();
    }
}
