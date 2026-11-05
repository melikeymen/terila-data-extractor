package com.terila.extractor.util;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Helpers for walking registries deterministically. */
public final class RegistryUtil {

    private RegistryUtil() {
    }

    /** Registry entries sorted by identifier, the canonical iteration order for output. */
    public static <T> List<Map.Entry<ResourceKey<T>, T>> entries(Registry<T> registry) {
        return registry.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().identifier().toString()))
                .toList();
    }

    /** Registry keys sorted by their string form, for stable manifests. */
    public static <T> List<Identifier> keys(Registry<T> registry) {
        return registry.keySet().stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .toList();
    }

    /** Convenience wrapper around the context lookup, used by extractors and tests. */
    public static <T> Optional<Registry<T>> lookup(ExtractorContext context, ResourceKey<Registry<T>> key) {
        return context.registry(key);
    }

    /** Renders a generic registry listing: identifier plus protocol id. */
    public static <T> JsonArray dump(Registry<T> registry, Function<T, JsonObject> mapper) {
        JsonArray array = new JsonArray();
        for (Map.Entry<ResourceKey<T>, T> entry : entries(registry)) {
            array.add(mapper.apply(entry.getValue()));
        }
        return array;
    }

    /** {@code {"id": "...", "protocol_id": n}} for one registry entry. */
    public static <T> JsonObject identity(Registry<T> registry, T value, JsonObject target) {
        Identifier id = registry.getKey(value);
        target.addProperty("id", id != null ? id.toString() : "unknown");
        int protocolId = registry.getId(value);
        if (protocolId >= 0) {
            target.addProperty("protocol_id", protocolId);
        }
        return target;
    }

    /** All registries of an access, sorted by key, for the generic registry extractor. */
    public static List<RegistryAccess.RegistryEntry<?>> all(RegistryAccess.Frozen access) {
        return access.registries()
                .sorted(Comparator.comparing(entry -> entry.key().identifier().toString()))
                .toList();
    }
}
