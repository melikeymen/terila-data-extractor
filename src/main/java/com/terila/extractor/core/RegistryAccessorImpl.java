package com.terila.extractor.core;

// Written by AI
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;

import java.util.Objects;
import java.util.Optional;

/** Exposes every registry of a {@link RegistryAccess.Frozen} unchanged. */
public class RegistryAccessorImpl implements RegistryAccessor {

    private final RegistryAccess.Frozen delegate;

    /** Wraps a non-null frozen registry access. */
    public RegistryAccessorImpl(RegistryAccess.Frozen delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Looks up a registry without filtering. */
    @Override
    public <T> Optional<Registry<T>> registry(ResourceKey<Registry<T>> key) {
        return delegate.lookup(key);
    }

    /** Every registry in the wrapped access. */
    @Override
    public Iterable<RegistryAccess.RegistryEntry<?>> registries() {
        return delegate.registries().toList();
    }

    /** The wrapped access itself. */
    @Override
    public RegistryAccess.Frozen raw() {
        return delegate;
    }
}
