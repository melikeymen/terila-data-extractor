package com.terila.extractor.core;

// Written by AI
import java.nio.file.Path;

/** Creates the {@link JsonWriter} matching a {@link ExtractorConfig}. */
public final class JsonWriterFactory {

    private JsonWriterFactory() {
    }

    /** Creates a writer whose formatting and compression match the run configuration. */
    public static JsonWriter create(Path outputRoot, ExtractorConfig config) {
        return config.prettyPrint()
                ? new PrettyPrintJsonWriter(outputRoot, config.gzip())
                : new CompactJsonWriter(outputRoot, config.gzip());
    }

    /** Creates an uncompressed, indented writer for tests and ad-hoc tooling. */
    public static JsonWriter pretty(Path outputRoot) {
        return new PrettyPrintJsonWriter(outputRoot);
    }

    /** Creates an uncompressed, single-line writer for tests and ad-hoc tooling. */
    public static JsonWriter compact(Path outputRoot) {
        return new CompactJsonWriter(outputRoot);
    }
}
