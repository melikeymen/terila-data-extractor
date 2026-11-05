package com.terila.extractor.core;

// Written by AI
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;

import java.util.List;
import java.util.Optional;

/**
 * Restricts a {@link RegistryAccessor} to the registries loaded from datapacks.
 *
 * <p>World generation data (features, structures, noise settings, density functions, ...) lives
 * here, so the worldgen extractors read through this view.
 */
public final class DynamicRegistryAccessor implements RegistryAccessor {

    /** Prefix identifying datapack-loaded worldgen registries. */
    private static final String WORLDPACK_PREFIX = "minecraft:worldgen/";

    private final RegistryAccess.Frozen delegate;

    /** Wraps the full access; lookups are filtered by {@link #isDynamic}. */
    public DynamicRegistryAccessor(RegistryAccess.Frozen delegate) {
        this.delegate = delegate;
    }

    /** Whether a registry is loaded from a data pack rather than from the built-in table. */
    public static boolean isDynamic(String registryId) {
        // Worldgen registries are datapack driven. A few non-worldgen exceptions are also loaded
        // from datapacks (trades, test data, dialogs), so they are included explicitly.
        return registryId.startsWith(WORLDPACK_PREFIX)
                || registryId.equals("minecraft:villager_trade")
                || registryId.equals("minecraft:trade_set")
                || registryId.equals("minecraft:test_environment")
                || registryId.equals("minecraft:test_instance")
                || registryId.equals("minecraft:dialog")
                || registryId.equals("minecraft:timeline")
                || registryId.equals("minecraft:world_clock")
                || registryId.equals("minecraft:sulfur_cube_archetype");
    }

    /** Looks up a registry only when it is data-pack driven. */
    @Override
    public <T> Optional<Registry<T>> registry(ResourceKey<Registry<T>> key) {
        if (!isDynamic(key.identifier().toString())) {
            return Optional.empty();
        }
        return delegate.lookup(key);
    }

    /** Snapshot of the data-pack-driven registries only. */
    @Override
    public Iterable<RegistryAccess.RegistryEntry<?>> registries() {
        List<RegistryAccess.RegistryEntry<?>> dynamic = delegate.registries()
                .filter(entry -> isDynamic(entry.key().identifier().toString()))
                .toList();
        return dynamic;
    }

    /** Underlying full access, for callers that need an unfiltered registry. */
    @Override
    public RegistryAccess.Frozen raw() {
        return delegate;
    }
}
