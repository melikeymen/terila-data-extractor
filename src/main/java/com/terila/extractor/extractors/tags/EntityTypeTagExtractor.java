package com.terila.extractor.extractors.tags;

// Written by AI
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;

/** Writes {@code tags/entities.json} (e.g. {@code bosses}, {@code raiders}). */
public final class EntityTypeTagExtractor extends TagExtractor<EntityType<?>> {

    public EntityTypeTagExtractor() {
        super("entity_tags", "tags/entities.json", Registries.ENTITY_TYPE);
    }
}
