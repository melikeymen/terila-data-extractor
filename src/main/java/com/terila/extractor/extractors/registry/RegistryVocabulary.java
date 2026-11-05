package com.terila.extractor.extractors.registry;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.Map;
import java.util.TreeMap;

/**
 * Attaches a type registry's contents to a document as a vocabulary.
 *
 * <p>Many data files are a {@code type} field plus per type fields, and the set of legal
 * {@code type} values lives in a registry of serialisers. When vanilla ships no data files for one of
 * those directories (item modifiers, functions) the vocabulary is the only information there is, so
 * it is written next to the data:
 *
 * <pre>
 * "type_vocabulary": {
 *   "minecraft:loot_function_type": { "minecraft:set_count": 1, "minecraft:enchant_randomly": 22, ... }
 * }
 * </pre>
 *
 * <p>The value of each entry is the protocol id a client uses to identify that type on the wire,
 * or {@code -1} when the registry is not synchronised.
 */
public final class RegistryVocabulary {

    private RegistryVocabulary() {
    }

    /**
     * Writes the given registries into {@code root} under {@code type_vocabulary}.
     *
     * @param context     the run context; registries are read from the loaded data set.
     * @param root        document to decorate.
     * @param registryIds identifiers of the type registries, e.g. {@code minecraft:loot_function_type}.
     */
    public static void describe(ExtractorContext context, JsonObject root, String... registryIds) {
        JsonObject vocabulary = new JsonObject();
        JsonArray families = new JsonArray();
        int total = 0;

        for (String id : registryIds) {
            Map<String, Integer> types = types(context, id);
            if (types.isEmpty()) {
                continue;
            }
            JsonObject family = new JsonObject();
            types.forEach(family::addProperty);
            vocabulary.add(id, family);
            families.add(id);
            total += types.size();
        }

        if (vocabulary.isEmpty()) {
            return;
        }
        root.addProperty("type_count", total);
        root.add("vocabulary_registries", families);
        root.add("type_vocabulary", vocabulary);
    }

    /** {@code type id -> protocol id} for one registry, sorted by type id. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Integer> types(ExtractorContext context, String registryId) {
        Map<String, Integer> types = new TreeMap<>();
        Identifier identifier = registryId.contains(":")
                ? Identifier.tryParse(registryId)
                : Identifier.withDefaultNamespace(registryId);
        if (identifier == null) {
            return types;
        }

        ResourceKey key = ResourceKey.createRegistryKey(identifier);
        Registry registry = (Registry) context.registryAccess().lookup(key).orElse(null);
        if (registry == null) {
            return types;
        }

        for (Object value : registry) {
            Identifier typeId = registry.getKey(value);
            if (typeId == null) {
                continue;
            }
            int protocolId = registry.getId(value);
            types.put(typeId.toString(), protocolId < 0 ? -1 : protocolId);
        }
        return types;
    }

    /** Convenience for documents that only need a count. */
    public static int size(ExtractorContext context, String registryId) {
        return types(context, registryId).size();
    }

    /** Exposed so callers can check whether a vocabulary was attached at all. */
    public static boolean isEmpty(JsonObject root) {
        JsonElement vocabulary = root.get("type_vocabulary");
        return vocabulary == null || !vocabulary.isJsonObject() || vocabulary.getAsJsonObject().isEmpty();
    }
}
