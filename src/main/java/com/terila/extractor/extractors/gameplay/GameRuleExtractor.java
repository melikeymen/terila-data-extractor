package com.terila.extractor.extractors.gameplay;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.gamerules.GameRule;

import java.io.IOException;
import java.util.Map;

/**
 * Writes {@code gameplay/game_rules.json}: every registered game rule with its category, type and
 * default value.
 *
 * <p>The generic registry dump records only an id and protocol id for built-in registries. A game
 * rule is more useful than that: a server wrapper, world preset tool or configuration editor needs
 * the default value and the value type to create a valid rule set. The values are read from the
 * registry's own {@link GameRule} objects, so the document cannot drift from the game's defaults.
 */
public final class GameRuleExtractor extends AbstractExtractor {

    public GameRuleExtractor() {
        super("game_rules", DataType.MISC, "gameplay/game_rules.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Registry<GameRule<?>> rules = super.registry(context, Registries.GAME_RULE).orElse(null);
        if (rules == null) {
            return 0;
        }

        JsonArray data = new JsonArray();
        for (Map.Entry<ResourceKey<GameRule<?>>, GameRule<?>> entry : sortedEntries(rules)) {
            data.add(describe(entry.getKey().identifier().toString(), entry.getValue()));
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.addProperty("rule_count", data.size());
        root.add("data", data);
        write(context, root);
        return data.size();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonObject describe(String id, GameRule<?> rawRule) {
        GameRule rule = rawRule;
        Object defaultValue = rule.defaultValue();

        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("description_id", rule.getDescriptionId());
        json.addProperty("category", rule.category().id().toString());
        json.addProperty("type", rule.gameRuleType().getSerializedName());
        json.addProperty("value_class", rule.valueClass().getSimpleName());
        if (defaultValue instanceof Boolean bool) {
            json.addProperty("default_value", bool);
        } else if (defaultValue instanceof Number number) {
            json.addProperty("default_value", number);
        } else if (defaultValue != null) {
            json.addProperty("default_value", String.valueOf(defaultValue));
        } else {
            json.add("default_value", JsonNull.INSTANCE);
        }
        json.addProperty("default_value_text", rule.serialize(defaultValue));
        return json;
    }
}

