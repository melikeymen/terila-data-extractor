package com.terila.extractor.extractors.blocks.model;

// Written by AI
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single block state.
 *
 * @param id         global block state id (the network id used by the protocol).
 * @param blockId    owning block identifier.
 * @param properties property name to value, in declaration order.
 */
public record BlockStateData(int id, String blockId, Map<String, String> properties) {

    /**
     * State without properties, used for blocks whose states carry no values.
     *
     * @param id      global block state id.
     * @param blockId owning block identifier.
     */
    public BlockStateData(int id, String blockId) {
        this(id, blockId, new LinkedHashMap<>());
    }

    /**
     * @param includeBlockId whether {@code block_id} is repeated in the output; it is redundant in
     *                       the per-block state files, whose name already carries the block.
     * @return the state as {@code {id, properties[, block_id]}}.
     */
    public JsonObject toJson(boolean includeBlockId) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        JsonObject props = new JsonObject();
        properties.forEach(props::addProperty);
        json.add("properties", props);
        if (includeBlockId) {
            json.addProperty("block_id", blockId);
        }
        return json;
    }
}
