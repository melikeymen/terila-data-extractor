package com.terila.extractor.extractors.entities.model;

// Written by AI
import com.google.gson.JsonObject;

/**
 * A registered entity type.
 *
 * @param id                    registry identifier.
 * @param protocolId            network id.
 * @param name                  display name.
 * @param width                 hitbox width.
 * @param height                hitbox height.
 * @param category              mob category ({@code MONSTER}, {@code CREATURE}, ...).
 * @param fireImmune            whether the entity is immune to fire.
 * @param summonable            whether it can be summoned with {@code /summon}.
 * @param spawnFarFromPlayer    whether it may spawn far away from players.
 * @param translationKey        description id, e.g. {@code entity.minecraft.zombie}.
 */
public record EntityData(
        String id,
        int protocolId,
        String name,
        float width,
        float height,
        String category,
        boolean fireImmune,
        boolean summonable,
        boolean spawnFarFromPlayer,
        String translationKey
) {

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("protocol_id", protocolId);
        if (name != null) {
            json.addProperty("name", name);
        }
        json.addProperty("width", width);
        json.addProperty("height", height);
        json.addProperty("category", category);
        json.addProperty("fire_immune", fireImmune);
        json.addProperty("summonable", summonable);
        json.addProperty("spawnable_far_from_player", spawnFarFromPlayer);
        if (translationKey != null) {
            json.addProperty("translation_key", translationKey);
        }
        return json;
    }
}
