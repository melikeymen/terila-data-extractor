package com.terila.extractor.core;

// Written by AI
import java.nio.file.Path;

/** Writes minified JSON, meant for shipping to the Terila server. */
public final class CompactJsonWriter extends GsonJsonWriter {

    public CompactJsonWriter(Path outputRoot) {
        this(outputRoot, false);
    }

    public CompactJsonWriter(Path outputRoot, boolean gzip) {
        super(outputRoot, false, gzip);
    }
}
