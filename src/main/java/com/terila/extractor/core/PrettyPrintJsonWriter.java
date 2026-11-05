package com.terila.extractor.core;

// Written by AI
import java.nio.file.Path;

/** Writes indented JSON, meant for review and diffing. */
public final class PrettyPrintJsonWriter extends GsonJsonWriter {

    public PrettyPrintJsonWriter(Path outputRoot) {
        this(outputRoot, false);
    }

    public PrettyPrintJsonWriter(Path outputRoot, boolean gzip) {
        super(outputRoot, true, gzip);
    }
}
