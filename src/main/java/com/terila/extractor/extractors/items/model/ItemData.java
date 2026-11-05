package com.terila.extractor.extractors.items.model;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * Everything the engine needs about a single item.
 *
 * @param id             registry identifier.
 * @param protocolId     network id.
 * @param name           display name.
 * @param maxStackSize   maximum stack size.
 * @param maxDamage      durability, {@code null} when the item is not damageable.
 * @param damageable     whether the item takes damage.
 * @param food           whether the item is edible.
 * @param fireResistant  whether the item survives fire/lava.
 * @param rarity         rarity name.
 * @param enchantability enchantability value, {@code -1} when absent.
 * @param translationKey description id, e.g. {@code item.minecraft.diamond_sword}. Always present;
 *                       the display name is resolved from the language files by the engine.
 * @param components     full data component map, name to serialised value.
 * @param componentOrder component names, sorted, for stable output. The game iterates its component
 *                       map in identity hash order, which differs between JVMs, so the order is
 *                       normalised here.
 * @param componentsBound whether the component map could be read at all. Components are only bound
 *                       once the game has initialised them, which requires a loaded datapack; a
 *                       head-less run without one reports {@code false} and leaves the derived
 *                       fields at their unknown values.
 */
public record ItemData(
        String id,
        int protocolId,
        String name,
        int maxStackSize,
        Integer maxDamage,
        boolean damageable,
        boolean food,
        boolean fireResistant,
        String rarity,
        int enchantability,
        String translationKey,
        Map<String, JsonElement> components,
        List<String> componentOrder,
        boolean componentsBound
) {

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("protocol_id", protocolId);
        if (name != null) {
            json.addProperty("name", name);
        }
        json.addProperty("max_stack_size", maxStackSize);
        if (maxDamage != null) {
            json.addProperty("max_damage", maxDamage);
        }
        json.addProperty("is_damageable", damageable);
        json.addProperty("is_food", food);
        json.addProperty("is_fire_resistant", fireResistant);
        if (rarity != null) {
            json.addProperty("rarity", rarity);
        }
        json.addProperty("enchantability", enchantability);
        if (translationKey != null) {
            json.addProperty("translation_key", translationKey);
        }

        JsonObject componentJson = new JsonObject();
        for (String component : componentOrder) {
            JsonElement value = components.get(component);
            componentJson.add(component, value);
        }
        json.add("components", componentJson);
        json.addProperty("component_count", componentOrder.size());

        JsonArray names = new JsonArray();
        componentOrder.forEach(names::add);
        json.add("component_ids", names);
        json.addProperty("components_bound", componentsBound);

        return json;
    }
}
