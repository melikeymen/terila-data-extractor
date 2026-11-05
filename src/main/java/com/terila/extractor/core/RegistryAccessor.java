package com.terila.extractor.core;

// Written by AI
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;

import java.util.Optional;

/**
 * Central access point for Minecraft registries.
 *
 * <p>Extractors never touch {@link RegistryAccess} directly: this interface is the seam that lets
 * the same extractor run against the built-in registries, the client-synced registries or the
 * datapack-driven dynamic registries.
 */
public interface RegistryAccessor {

    /** Looks a registry up by its key. */
    <T> Optional<Registry<T>> registry(ResourceKey<Registry<T>> key);

    /** Every registry this accessor exposes. */
    Iterable<RegistryAccess.RegistryEntry<?>> registries();

    /** The underlying frozen registry access. */
    RegistryAccess.Frozen raw();
}
