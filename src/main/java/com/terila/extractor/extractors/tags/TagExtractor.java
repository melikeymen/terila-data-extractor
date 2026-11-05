package com.terila.extractor.extractors.tags;

// Written by AI
import com.google.gson.JsonArray;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.tags.model.TagData;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Base class for tag extraction, parameterised by the tagged element type.
 *
 * <p>Tags are read from the runtime tag bindings, so every nested {@code #tag} reference has
 * already been resolved and merged by the game. What comes out is the <em>effective</em> tag
 * content — exactly what a server implementation needs. The original, unresolved datapack structure
 * is preserved by the datapack JSON extractors.
 *
 * @param <T> type of the element the tags collect, e.g. {@code Block} or {@code Item}.
 */
public abstract class TagExtractor<T> extends AbstractExtractor {

    private final ResourceKey<Registry<T>> registryKey;

    /**
     * @param name        stable identifier reported in logs and {@code _meta}.
     * @param outputPath  document written relative to the output root.
     * @param registryKey registry whose tags are read.
     */
    protected TagExtractor(String name, String outputPath, ResourceKey<Registry<T>> registryKey) {
        super(name, DataType.TAGS, outputPath);
        this.registryKey = registryKey;
    }

    public ResourceKey<Registry<T>> registryKey() {
        return registryKey;
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Registry<T> registry = context.registry(registryKey).orElse(null);
        if (registry == null) {
            context.errors().warning("REGISTRY_NOT_FOUND", name(), registryKey.identifier().toString(),
                    "Tag source registry is unavailable in this environment");
            return 0;
        }

        List<TagData> tags = collect(registry);
        JsonArray data = new JsonArray();
        tags.forEach(tag -> data.add(tag.toJson()));
        writeEnvelope(context, data, tags.size());
        return tags.size();
    }

    /**
     * Resolves every tag of a registry into a sorted list.
     *
     * @param registry registry to read the bound tags from.
     * @return one entry per tag, elements sorted, nested references already resolved.
     */
    protected List<TagData> collect(Registry<T> registry) {
        List<TagData> tags = new ArrayList<>();
        registry.getTags().forEach(named -> {
            List<String> values = new ArrayList<>();
            for (Holder<T> holder : named) {
                holder.unwrapKey().ifPresent(key -> values.add(key.identifier().toString()));
            }
            values.sort(Comparator.naturalOrder());
            tags.add(new TagData(named.key().location().toString(), List.copyOf(values), false));
        });
        tags.sort(Comparator.comparing(TagData::id));
        return tags;
    }
}
