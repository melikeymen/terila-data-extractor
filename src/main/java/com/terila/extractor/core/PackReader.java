package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;

import java.util.Map;

/**
 * Reads raw files from a data pack.
 *
 * <p>Some data has no runtime registry to walk — advancements, loot tables, predicates, item
 * modifiers, function tags and {@code .mcfunction} sources are plain files. Both possible sources
 * implement this interface: a filesystem directory ({@link DatapackReader}) and the loaded data packs
 * ({@link ResourcePackReader}, which also reaches the vanilla pack inside the server jar).
 */
public interface PackReader {

    /** Whether the underlying source can actually be read. */
    boolean isAvailable();

    /** Human readable description of the source, used in warnings. */
    String describe();

    /**
     * Loads every JSON file below {@code <source>/data/<namespace>/<directory>}.
     *
     * @return entries keyed by {@code namespace:relativePathWithoutExtension}, sorted by key.
     */
    Map<String, JsonElement> loadAll(String directory);

    /**
     * Loads every file with the given extension below {@code <source>/data/<namespace>/<directory>}.
     *
     * <p>Used for {@code .mcfunction}: those files are commands, not JSON, so they cannot go through
     * {@link #loadAll}. The key follows the same rule as the JSON reader, which makes a function id
     * look exactly like the id a function tag can reference.
     *
     * @param directory directory inside {@code data/<namespace>/}, e.g. {@code function}.
     * @param extension extension including the dot, e.g. {@code .mcfunction}.
     * @return entries keyed by {@code namespace:relativePathWithoutExtension}, sorted by key.
     */
    Map<String, String> loadTextAll(String directory, String extension);
}

