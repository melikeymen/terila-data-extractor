package com.terila.extractor.core;

// Written by AI
import java.io.IOException;
import java.nio.file.Path;

/** Raised when another extraction already holds the lock for an output directory. */
public final class OutputLockedException extends IOException {

    private final Path outputRoot;
    private final Path lockFile;

    OutputLockedException(Path outputRoot, Path lockFile, String owner, Throwable cause) {
        super("Another extraction is already writing to " + outputRoot
                + (owner == null ? "" : " [" + owner + "]")
                + " (lock file: " + lockFile + ")", cause);
        this.outputRoot = outputRoot;
        this.lockFile = lockFile;
    }

    Path outputRoot() {
        return outputRoot;
    }

    Path lockFile() {
        return lockFile;
    }
}

