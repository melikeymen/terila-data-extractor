package com.terila.extractor.extractors.items.model;

// Written by AI
import com.google.gson.JsonObject;

/**
 * A data component type.
 *
 * @param id         component identifier, e.g. {@code minecraft:max_stack_size}.
 * @param protocolId network id.
 * @param valueType  runtime class name of the value type; useful when debugging serialisation.
 */
public record ComponentData(String id, int protocolId, String valueType) {

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("protocol_id", protocolId);
        json.addProperty("value_type", valueType);
        return json;
    }
}
