package com.terila.extractor.extractors.registry;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;

import java.io.IOException;
import java.util.Map;

/**
 * Dumps a registry by serialising each entry through its own codec.
 *
 * <p>This is what makes broad coverage affordable: biomes, dimensions, features, structures, noise
 * settings, enchantments, damage types, trim materials/patterns, dialogs, variants and every other
 * datapack registry is emitted with full fidelity without a hand written extractor per registry.
 *
 * <p>Serialisation happens under {@link RegistryOps} so nested references to other registries
 * (a biome referencing a placed feature, a structure referencing a biome tag, ...) resolve to
 * identifiers instead of failing. Each entry is written through the registry's own <em>element</em>
 * codec ({@link RegistryCodecs}); in 26.3 {@code Registry.byNameCodec()} only emits the entry's name,
 * so using it would produce a file of bare identifiers.
 *
 * @param <T> element type the dumped registry holds.
 */
public class CodecRegistryDumpExtractor<T> extends AbstractExtractor {

    protected final ResourceKey<Registry<T>> registryKey;

    /**
     * @param name        stable identifier reported in logs and {@code _meta}.
     * @param type        category that controls whether the extractor runs.
     * @param outputPath  document written relative to the output root.
     * @param registryKey registry to dump.
     */
    public CodecRegistryDumpExtractor(String name, DataType type, String outputPath,
                                      ResourceKey<Registry<T>> registryKey) {
        super(name, type, outputPath);
        this.registryKey = registryKey;
    }

    /** The registry this extractor dumps. */
    public ResourceKey<Registry<T>> registryKey() {
        return registryKey;
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Registry<T> registry = context.registry(registryKey).orElse(null);
        if (registry == null) {
            context.errors().warning("REGISTRY_NOT_FOUND", name(), registryKey.identifier().toString(),
                    "Registry is not available in this environment");
            return 0;
        }

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, context.lookup());
        JsonArray data = new JsonArray();
        int count = 0;

        Codec<T> elementCodec = RegistryCodecs.elementCodec(registry);
        if (elementCodec == null) {
            // Not a data pack registry: there is no payload to serialise, so the entries are still
            // written by identity rather than being silently dropped.
            context.errors().warning("REGISTRY_HAS_NO_ELEMENT_CODEC", name(),
                    registryKey.identifier().toString(),
                    "Registry is not data pack loaded; entries are emitted by identity only "
                            + "(registries/dump/ carries the same registry)");
            return writeIdentity(context, registry);
        }

        for (Map.Entry<ResourceKey<T>, T> entry : com.terila.extractor.util.RegistryUtil.entries(registry)) {
            String id = entry.getKey().identifier().toString();
            try {
                JsonElement encoded = encode(elementCodec, ops, entry.getValue());
                JsonObject json = identity(registry, entry);
                if (encoded != null) {
                    json.add("data", encoded);
                } else {
                    context.errors().warning("REGISTRY_ENCODE_FAILED", name(), id,
                            "Registry codec returned no result; the entry was written by identity");
                }
                data.add(json);
                count++;
            } catch (RuntimeException failure) {
                context.errors().error("REGISTRY_ENTRY_FAILED", name(), id, null, String.valueOf(failure));
            }
        }

        writeEnvelope(context, data, count);
        return count;
    }

    /**
     * Encodes one entry, returning {@code null} rather than throwing when the codec rejects it.
     *
     * <p>Canonicalised for the same reason as in {@link AllRegistriesExtractor}: codec output that
     * contains component maps would otherwise order its members by identity hash.
     */
    private static <T> JsonElement encode(Codec<T> codec, RegistryOps<JsonElement> ops, T value) {
        return com.terila.extractor.util.JsonUtil.canonical(
                codec.encodeStart(ops, value).result().orElse(null));
    }

    /** {@code {"id": ..., "protocol_id": n}} for one entry. */
    private static <T> JsonObject identity(Registry<T> registry,
                                           Map.Entry<ResourceKey<T>, T> entry) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entry.getKey().identifier().toString());
        int protocolId = registry.getId(entry.getValue());
        if (protocolId >= 0) {
            json.addProperty("protocol_id", protocolId);
        }
        return json;
    }

    /** Fallback for a registry with no element codec: identity only. */
    private int writeIdentity(ExtractorContext context, Registry<T> registry) throws IOException {
        JsonArray data = new JsonArray();
        for (Map.Entry<ResourceKey<T>, T> entry
                : com.terila.extractor.util.RegistryUtil.entries(registry)) {
            data.add(identity(registry, entry));
        }
        writeEnvelope(context, data, data.size());
        return data.size();
    }
}

