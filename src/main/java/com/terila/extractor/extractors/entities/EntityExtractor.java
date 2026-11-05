package com.terila.extractor.extractors.entities;

// Written by AI
import com.google.gson.JsonArray;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.entities.model.EntityData;
import com.terila.extractor.util.MathUtil;
import com.terila.extractor.util.TextUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts every registered entity type into {@code entities/minecraft.json}.
 *
 * <p>The registry scan is shared with the other entity extractors through {@link #catalog}, so it
 * runs exactly once: serialising the derivation per type is cheap, but resolving a display name
 * touches the game's unsynchronised component cache and must not be done twice in parallel.
 */
public final class EntityExtractor extends AbstractExtractor {

    public EntityExtractor() {
        super("entities", DataType.ENTITIES, "entities/minecraft.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        List<EntityData> entities = catalog(context);

        JsonArray data = new JsonArray();
        entities.forEach(entity -> data.add(entity.toJson()));
        writeEnvelope(context, data, entities.size());
        return entities.size();
    }

    /** Cached entity list shared with the other entity extractors. */
    public static List<EntityData> catalog(ExtractorContext context) {
        return context.shared(EntityCatalog.class,
                () -> new EntityCatalog(scan(context))).entities();
    }

    private static List<EntityData> scan(ExtractorContext context) {
        var registry = context.registry(Registries.ENTITY_TYPE).orElse(BuiltInRegistries.ENTITY_TYPE);
        List<EntityData> entities = new ArrayList<>();
        for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> entry
                : com.terila.extractor.util.RegistryUtil.entries(registry)) {
            EntityType<?> type = entry.getValue();
            try {
                int protocolId = registry.getId(type);
                entities.add(new EntityData(
                        entry.getKey().identifier().toString(),
                        protocolId < 0 ? -1 : protocolId,
                        TextUtil.resolve(type::getDescription),
                        MathUtil.round(type.getWidth()),
                        MathUtil.round(type.getHeight()),
                        type.getCategory().name().toLowerCase(Locale.ROOT),
                        type.fireImmune(),
                        type.canSummon(),
                        type.canSpawnFarFromPlayer(),
                        type.getDescriptionId()
                ));
            } catch (RuntimeException failure) {
                context.errors().error("ENTITY_EXTRACTION_FAILED", "entities",
                        entry.getKey().identifier().toString(), null, String.valueOf(failure));
            }
        }
        return List.copyOf(entities);
    }

    record EntityCatalog(List<EntityData> entities) {
    }
}
