package com.terila.extractor.core;

// Written by AI
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Restricts a {@link RegistryAccessor} to the registries Minecraft synchronises to clients.
 *
 * <p>These are the registries a protocol implementation must know about, so the packet and
 * registry extractors read through this view.
 */
public final class SyncedRegistryAccessor implements RegistryAccessor {

    /** Registry identifiers synchronised to the client in 26.2. */
    public static final Set<String> SYNCED_REGISTRIES = Set.of(
            "minecraft:dimension_type",
            "minecraft:worldgen/biome",
            "minecraft:chat_type",
            "minecraft:damage_type",
            "minecraft:enchantment",
            "minecraft:instrument",
            "minecraft:jukebox_song",
            "minecraft:painting_variant",
            "minecraft:trim_material",
            "minecraft:trim_pattern",
            "minecraft:wolf_variant",
            "minecraft:wolf_sound_variant",
            "minecraft:cat_variant",
            "minecraft:cat_sound_variant",
            "minecraft:frog_variant",
            "minecraft:pig_variant",
            "minecraft:pig_sound_variant",
            "minecraft:cow_variant",
            "minecraft:cow_sound_variant",
            "minecraft:chicken_variant",
            "minecraft:chicken_sound_variant",
            "minecraft:zombie_nautilus_variant",
            "minecraft:banner_pattern",
            "minecraft:world_clock",
            "minecraft:timeline",
            "minecraft:test_environment",
            "minecraft:test_instance",
            "minecraft:dialog",
            // New in 26.2.
            "minecraft:sulfur_cube_archetype"
    );

    private final RegistryAccess.Frozen delegate;

    /** Wraps the full access; lookups are filtered by {@link #SYNCED_REGISTRIES}. */
    public SyncedRegistryAccessor(RegistryAccess.Frozen delegate) {
        this.delegate = delegate;
    }

    /** Whether a registry identifier is part of the client-synchronised set. */
    public static boolean isSynced(String registryId) {
        return SYNCED_REGISTRIES.contains(registryId);
    }

    /** Looks up a registry only when it is client-synchronised. */
    @Override
    public <T> Optional<Registry<T>> registry(ResourceKey<Registry<T>> key) {
        if (!isSynced(key.identifier().toString())) {
            return Optional.empty();
        }
        return delegate.lookup(key);
    }

    /** Snapshot of the client-synchronised registries only. */
    @Override
    public Iterable<RegistryAccess.RegistryEntry<?>> registries() {
        List<RegistryAccess.RegistryEntry<?>> synced = delegate.registries()
                .filter(entry -> isSynced(entry.key().identifier().toString()))
                .toList();
        return synced;
    }

    /** Underlying full access, for callers that need an unfiltered registry. */
    @Override
    public RegistryAccess.Frozen raw() {
        return delegate;
    }
}
