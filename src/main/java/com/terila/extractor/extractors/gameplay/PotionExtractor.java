package com.terila.extractor.extractors.gameplay;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;

import java.io.IOException;
import java.util.Map;

/**
 * Writes {@code gameplay/potions.json}: every potion and its effect instances.
 *
 * <p>The potion registry is built-in, so the generic registry dump can only preserve its ids. The
 * actual game object exposes the ordered effect list, including duration, amplifier and display
 * flags, which is the part a combat or brewing system needs. The entries are read from the live
 * registry rather than a hand-maintained table, so a new potion or effect is covered automatically.
 */
public final class PotionExtractor extends AbstractExtractor {

    public PotionExtractor() {
        super("potions", DataType.MISC, "gameplay/potions.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Registry<Potion> potions = super.registry(context, Registries.POTION).orElse(null);
        if (potions == null) {
            return 0;
        }

        JsonArray data = new JsonArray();
        int totalEffects = 0;
        for (Map.Entry<ResourceKey<Potion>, Potion> entry : sortedEntries(potions)) {
            Potion potion = entry.getValue();
            JsonArray effects = new JsonArray();
            for (MobEffectInstance effect : potion.getEffects()) {
                JsonObject json = new JsonObject();
                json.addProperty("effect", effect.getEffect().unwrapKey()
                        .map(key -> key.identifier().toString())
                        .orElse("minecraft:unknown"));
                json.addProperty("description_id", effect.getDescriptionId());
                json.addProperty("duration_ticks", effect.getDuration());
                json.addProperty("amplifier", effect.getAmplifier());
                json.addProperty("ambient", effect.isAmbient());
                json.addProperty("visible", effect.isVisible());
                json.addProperty("show_icon", effect.showIcon());
                effects.add(json);
            }

            JsonObject json = new JsonObject();
            json.addProperty("id", entry.getKey().identifier().toString());
            json.addProperty("name", potion.name());
            json.addProperty("instant", potion.hasInstantEffects());
            json.addProperty("effect_count", effects.size());
            json.add("effects", effects);
            data.add(json);
            totalEffects += effects.size();
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.addProperty("potion_count", data.size());
        root.addProperty("total_effect_count", totalEffects);
        root.add("data", data);
        write(context, root);
        return data.size();
    }
}

