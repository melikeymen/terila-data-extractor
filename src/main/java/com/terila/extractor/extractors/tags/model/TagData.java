package com.terila.extractor.extractors.tags.model;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * A resolved tag.
 *
 * @param id       tag identifier, e.g. {@code minecraft:logs}.
 * @param values   every entry of the tag, resolved and flattened.
 * @param replace  whether the tag replaces rather than appends to its datapack definition.
 */
public record TagData(String id, List<String> values, boolean replace) {

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        json.add("values", array);
        json.addProperty("replace", replace);
        json.addProperty("value_count", values.size());
        return json;
    }
}
