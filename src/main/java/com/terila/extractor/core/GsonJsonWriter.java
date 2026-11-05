package com.terila.extractor.core;

// Written by AI
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialises JSON with Gson and persists it through {@link AtomicFileWriter}.
 *
 * <p>Gson is used in its default {@code HTML_SAFE} escaping mode so payloads stay valid inside HTML
 * contexts and diff cleanly against the vanilla reports.
 */
public class GsonJsonWriter implements JsonWriter {

    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private static final Gson COMPACT = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private final Path outputRoot;
    private final boolean pretty;
    private final boolean gzip;
    private final AtomicLong bytesWritten = new AtomicLong();

    /** Creates a writer rooted at {@code outputRoot}; the root is made absolute and normalised. */
    public GsonJsonWriter(Path outputRoot, boolean pretty, boolean gzip) {
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        this.pretty = pretty;
        this.gzip = gzip;
    }

    /** Serialises and atomically writes one document below the output root. */
    @Override
    public Path write(String relativePath, JsonElement payload) throws IOException {
        Path target = resolve(relativePath);
        String serialised = (pretty ? PRETTY : COMPACT).toJson(payload);
        AtomicFileWriter.writeString(target, gzip, serialised);
        bytesWritten.addAndGet(serialised.length());
        return target;
    }

    /** Resolves a relative path, rejecting traversal outside the output root. */
    @Override
    public Path resolve(String relativePath) {
        Path resolved = outputRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(outputRoot)) {
            throw new IllegalArgumentException("Output path escapes the output root: " + relativePath);
        }
        return resolved;
    }

    /** Absolute root every path is resolved against. */
    @Override
    public Path outputRoot() {
        return outputRoot;
    }

    /** Rough number of characters emitted through this writer, used for the run summary. */
    public long bytesWritten() {
        return bytesWritten.get();
    }
}
