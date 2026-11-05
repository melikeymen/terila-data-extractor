package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes JSON payloads to the output directory.
 *
 * <p>Implementations are responsible for atomicity: a partially written file must never be visible
 * to a reader.
 */
public interface JsonWriter {

    /**
     * Writes {@code payload} to {@code relativePath} underneath the configured output directory.
     *
     * @param relativePath path relative to the output root, e.g. {@code blocks/minecraft.json}.
     * @param payload      the JSON document to serialise.
     * @return the absolute path that was written.
     */
    Path write(String relativePath, JsonElement payload) throws IOException;

    /** Resolves a relative output path against the output root without writing anything. */
    Path resolve(String relativePath);

    /** Root directory every relative path is resolved against. */
    Path outputRoot();
}
