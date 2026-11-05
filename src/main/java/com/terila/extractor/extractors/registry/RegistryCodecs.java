package com.terila.extractor.extractors.registry;

// Written by AI
import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryDataLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the codec that serialises <em>one entry</em> of a registry.
 *
 * <p>Minecraft 26.3 changed the semantics of {@link Registry#byNameCodec()}: it is now a
 * name codec, encoding an entry as just its identifier. That is exactly what a reference needs, but
 * it is useless for a dump — every registry document would collapse to a list of names. The codec
 * that carries the payload is the one the game itself uses to read a data pack file:
 * {@link RegistryDataLoader.RegistryData#elementCodec()}.
 *
 * <p>The table is built once, lazily, from the three registry data lists the game declares — world
 * generation, dimensions and reloadable. Registries without an entry codec (every built-in registry,
 * which is not loaded from files) resolve to {@code null} and are emitted by identity instead.
 */
public final class RegistryCodecs {

    private static volatile Map<String, Codec<?>> elementCodecs;

    private RegistryCodecs() {
    }

    /** Registry identifiers Minecraft loads from a data pack, exactly the 60 the game declares. */
    public static Set<String> datapackRegistryIds() {
        return elementCodecs().keySet();
    }

    /**
     * Element codec of a registry, or {@code null} when the registry is not data pack loaded.
     *
     * <p>The cast is safe: the codec is keyed by the registry's own identifier, so it is the codec of
     * that registry's element type. The registry object does not carry its type at runtime.
     */
    @SuppressWarnings("unchecked")
    public static <T> Codec<T> elementCodec(Registry<T> registry) {
        if (registry == null) {
            return null;
        }
        return (Codec<T>) elementCodec(registry.key().identifier().toString());
    }

    /** Element codec of a registry by identifier, or {@code null} when it has none. */
    @SuppressWarnings("unchecked")
    public static Codec<Object> elementCodec(String registryId) {
        return (Codec<Object>) elementCodecs().get(registryId);
    }

    private static Map<String, Codec<?>> elementCodecs() {
        Map<String, Codec<?>> cached = elementCodecs;
        if (cached != null) {
            return cached;
        }
        synchronized (RegistryCodecs.class) {
            if (elementCodecs == null) {
                Map<String, Codec<?>> built = new LinkedHashMap<>();
                add(built, RegistryDataLoader.WORLD_REGISTRIES);
                add(built, RegistryDataLoader.DIMENSION_REGISTRIES);
                add(built, RegistryDataLoader.RELOADABLE_REGISTRIES);
                elementCodecs = Map.copyOf(built);
            }
            return elementCodecs;
        }
    }

    private static void add(Map<String, Codec<?>> target,
                            java.util.List<RegistryDataLoader.RegistryData<?>> data) {
        for (RegistryDataLoader.RegistryData<?> entry : data) {
            target.put(entry.key().identifier().toString(), entry.elementCodec());
        }
    }
}
