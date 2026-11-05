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
 * Writes {@code items/tools.json}: every item that declares a {@code minecraft:tool} component,
 * which is where mining speed, durability-per-block and the damage rules live in 26.2.
 */
public final class ItemToolExtractor extends AbstractExtractor {

    public ItemToolExtractor() {
        super("item_tools", DataType.ITEMS, "items/tools.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        List<ItemData> tools = ItemCatalog.of(context).withComponent("minecraft:tool");

        JsonObject byItem = new JsonObject();
        for (ItemData item : tools) {
            byItem.add(item.id(), item.components().get("minecraft:tool"));
        }

        JsonArray ids = new JsonArray();
        tools.forEach(item -> ids.add(item.id()));

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), tools.size()));
        root.add("items", ids);
        root.add("tool_components", byItem);
        write(context, root);
        return tools.size();
    }
}
