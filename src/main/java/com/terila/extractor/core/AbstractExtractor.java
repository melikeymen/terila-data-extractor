package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base class with the small helpers every extractor needs: registry lookup, deterministic sorting
 * and envelope writing.
 */
public abstract class AbstractExtractor implements DataExtractor {

    private final String name;
    private final DataType type;
    private final String outputPath;

    /**
     * @param name       stable identifier reported in logs and {@code _meta}.
     * @param type       category that controls whether the extractor runs.
     * @param outputPath document written relative to the output root.
     */
    protected AbstractExtractor(String name, DataType type, String outputPath) {
        this.name = name;
        this.type = type;
        this.outputPath = outputPath;
    }

    /** Stable extractor name used in reports and duplicate checks. */
    @Override
    public final String name() {
        return name;
    }

    /** Output category that controls whether this extractor runs. */
    @Override
    public final DataType type() {
        return type;
    }

    /** Document path this extractor writes, relative to the output root. */
    @Override
    public final String outputPath() {
        return outputPath;
    }

    /**
     * Looks a registry up in the context, reporting a warning when it is absent.
     *
     * @param <T>     element type the registry holds.
     * @param context the run's context.
     * @param key     registry to look up.
     * @return the registry, or empty after a warning has been recorded.
     */
    protected <T> Optional<Registry<T>> registry(ExtractorContext context, ResourceKey<Registry<T>> key) {
        Optional<Registry<T>> registry = context.registry(key);
        if (registry.isEmpty()) {
            context.errors().warning("REGISTRY_NOT_FOUND", name(), key.identifier().toString(),
                    "Registry is not available in this environment");
        }
        return registry;
    }

    /**
     * Iterates a registry's entries in identifier order so output is deterministic.
     *
     * @param <T>      element type the registry holds.
     * @param registry registry to walk.
     * @return its entries, sorted by identifier.
     */
    protected static <T> List<Map.Entry<net.minecraft.resources.ResourceKey<T>, T>> sortedEntries(
            Registry<T> registry) {
        return registry.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().identifier().toString()))
                .toList();
    }

    /**
     * Writes a document of the shape {@code {"_meta": {...}, "data": [...]}} .
     *
     * @param context    the run's context.
     * @param data       entries to write.
     * @param entryCount how many entries the extractor claims, which is what {@code _meta} records.
     * @throws IOException when the document could not be written.
     */
    protected void writeEnvelope(ExtractorContext context, JsonArray data, int entryCount) throws IOException {
        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), entryCount));
        root.add("data", data);
        context.writer().write(outputPath(), root);
    }

    /**
     * Writes a plain document without the meta envelope.
     *
     * @param context the run's context.
     * @param payload the exact document to store at {@link DataExtractor#outputPath()}.
     * @throws IOException when the document could not be written.
     */
    protected void write(ExtractorContext context, JsonElement payload) throws IOException {
        context.writer().write(outputPath(), payload);
    }
}
