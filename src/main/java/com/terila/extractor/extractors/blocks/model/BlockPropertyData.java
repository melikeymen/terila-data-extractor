package com.terila.extractor.extractors.blocks.model;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * One block state property ({@code facing}, {@code powered}, ...).
 *
 * @param name   property name.
 * @param type   the value class in snake_case, e.g. {@code enum} or {@code integer}.
 * @param values every accepted value, in the game's declaration order.
 */
public record BlockPropertyData(String name, String type, List<String> values) {

    /** {@code {name, type, values, value_count}} as written to {@code blocks/properties.json}. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("type", type);
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        json.add("values", array);
        json.addProperty("value_count", values.size());
        return json;
    }
}
