package com.terila.extractor.extractors.entities;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.util.RegistryUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Writes {@code entities/attributes.json}: every attribute with the values that are not part of its
 * identifier.
 *
 * <p>The attribute registry is built in ({@code minecraft:attribute} is not loaded from a data pack),
 * so it has no element codec and a codec dump would only repeat the names. What actually matters per
 * attribute is its default value, whether it is synchronised to clients and the translation key used
 * for its display name — all three are read from the live {@link Attribute} instances here.
 *
 * <p>The per-entity default attribute maps are still not exposed by any public API, so they are not
 * part of this document; the {@code entity} documents carry the entity types themselves.
 */
public final class EntityAttributeExtractor extends AbstractExtractor {

    private static final ResourceKey<net.minecraft.core.Registry<Attribute>> KEY = Registries.ATTRIBUTE;

    public EntityAttributeExtractor() {
        super("entity_attributes", DataType.ENTITIES, "entities/attributes.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        var registry = context.registry(KEY).orElse(null);
        if (registry == null) {
            context.errors().warning("REGISTRY_NOT_FOUND", name(), KEY.identifier().toString(),
                    "Registry is not available in this environment");
            return 0;
        }

        JsonArray data = new JsonArray();
        JsonObject syncableCounts = new JsonObject();
        Set<String> namespaces = new LinkedHashSet<>();

        for (Map.Entry<net.minecraft.resources.ResourceKey<Attribute>, Attribute> entry
                : RegistryUtil.entries(registry)) {
            String id = entry.getKey().identifier().toString();
            Attribute attribute = entry.getValue();

            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            int protocolId = registry.getId(attribute);
            if (protocolId >= 0) {
                json.addProperty("protocol_id", protocolId);
            }
            json.addProperty("default_value", attribute.getDefaultValue());
            json.addProperty("client_syncable", attribute.isClientSyncable());
            json.addProperty("description_id", attribute.getDescriptionId());
            // Example outputs of the attribute's own clamping rule, which is what a client needs to
            // reproduce an attribute value exactly.
            JsonArray sanitised = new JsonArray();
            for (double probe : new double[] {-1024.0, -1.0, 0.0, 1.0, 1024.0}) {
                sanitised.add(attribute.sanitizeValue(probe));
            }
            json.add("sanitize_samples", sanitised);
            data.add(json);

            String key = attribute.isClientSyncable() ? "client_syncable" : "server_only";
            syncableCounts.addProperty(key,
                    syncableCounts.has(key) ? syncableCounts.get(key).getAsInt() + 1 : 1);
            namespaces.add(entry.getKey().identifier().getNamespace());
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.add("sync", syncableCounts);
        root.add("namespaces", toArray(namespaces));
        root.add("data", data);
        write(context, root);
        return data.size();
    }

    private static JsonArray toArray(Set<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
