package com.terila.extractor.extractors.registry;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.io.IOException;
import java.util.Map;

/**
 * Dumps a registry as {@code {"id": ..., "protocol_id": ...}}.
 *
 * <p>Used for the registries that carry no extra data but must be known by name and network id:
 * sound events, particles, mob effects, menus, attributes, custom stats and the like.
 *
 * @param <T> element type the dumped registry holds.
 */
public class IdentityRegistryDumpExtractor<T> extends AbstractExtractor {

    protected final ResourceKey<Registry<T>> registryKey;

    /**
     * @param name        stable identifier reported in logs and {@code _meta}.
     * @param type        category that controls whether the extractor runs.
     * @param outputPath  document written relative to the output root.
     * @param registryKey registry to dump.
     */
    public IdentityRegistryDumpExtractor(String name, DataType type, String outputPath,
                                         ResourceKey<Registry<T>> registryKey) {
        super(name, type, outputPath);
        this.registryKey = registryKey;
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Registry<T> registry = context.registry(registryKey).orElse(null);
        if (registry == null) {
            context.errors().warning("REGISTRY_NOT_FOUND", name(), registryKey.identifier().toString(),
                    "Registry is not available in this environment");
            return 0;
        }

        JsonArray data = new JsonArray();
        int count = 0;
        for (Map.Entry<ResourceKey<T>, T> entry : com.terila.extractor.util.RegistryUtil.entries(registry)) {
            JsonObject json = new JsonObject();
            json.addProperty("id", entry.getKey().identifier().toString());
            int protocolId = registry.getId(entry.getValue());
            json.addProperty("protocol_id", protocolId < 0 ? -1 : protocolId);
            data.add(json);
            count++;
        }

        writeEnvelope(context, data, count);
        return count;
    }
}
