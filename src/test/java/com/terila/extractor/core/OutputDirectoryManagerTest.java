package com.terila.extractor.core;

// Written by AI
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies exactly what a clean removes and what it deliberately leaves alone. */
class OutputDirectoryManagerTest {

    @TempDir
    Path root;

    @Test
    void cleanRemovesTheManagedLayoutButKeepsUnrelatedFiles() throws IOException {
        OutputDirectoryManager output = new OutputDirectoryManager(JsonWriterFactory.pretty(root));
        output.prepare();
        write("blocks/old.json", "{}");
        write("_meta.json", "{\"old\":true}");
        write("_errors.json", "{}");
        write("_validation.json", "{}");
        write("_changelog.json", "{}");
        write("notes.txt", "keep");

        output.clean(false);

        assertFalse(Files.exists(root.resolve("blocks/old.json")));
        assertFalse(Files.exists(root.resolve("_meta.json")));
        assertFalse(Files.exists(root.resolve("_errors.json")));
        assertFalse(Files.exists(root.resolve("_validation.json")));
        assertFalse(Files.exists(root.resolve("_changelog.json")));
        assertTrue(Files.exists(root.resolve("notes.txt")),
                "a file outside the managed layout must survive");
        assertTrue(Files.isDirectory(root.resolve("blocks")));
        assertTrue(Files.isDirectory(root.resolve("structures/templates")));
    }

    @Test
    void cleanCanKeepThePreviousMetaForComparison() throws IOException {
        OutputDirectoryManager output = new OutputDirectoryManager(JsonWriterFactory.pretty(root));
        output.prepare();
        write("_meta.json", "{\"minecraft_version\":\"26.3\"}");
        write("_validation.json", "{}");

        output.clean(true);

        assertEquals("{\"minecraft_version\":\"26.3\"}",
                Files.readString(root.resolve("_meta.json"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.resolve("_validation.json")),
                "the validation report belongs to the previous run");
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}

