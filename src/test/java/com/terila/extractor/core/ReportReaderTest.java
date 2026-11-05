package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests that report discovery and the two accepted layouts work. */
class ReportReaderTest {

    @TempDir
    Path root;

    /** A data generator output directory holds its reports in a nested {@code reports} folder. */
    @Test
    void resolvesNestedReportsDirectory() throws IOException {
        Path reports = Files.createDirectories(root.resolve("reports"));
        Files.writeString(reports.resolve("registries.json"), "{\"minecraft:block\":{\"entries\":{}}}");
        Files.writeString(reports.resolve("packets.json"), "{\"play\":{\"clientbound\":{}}}");

        ReportReader reader = ReportReader.of(root);

        assertTrue(reader.isAvailable());
        assertEquals(reports, reader.root());
        assertTrue(reader.read("packets.json").isPresent());
    }

    /** Pointing straight at the reports directory has to work too. */
    @Test
    void acceptsReportsDirectoryDirectly() throws IOException {
        Files.writeString(root.resolve("registries.json"), "{}");

        ReportReader reader = ReportReader.of(root);

        assertTrue(reader.isAvailable());
        assertEquals(root, reader.root());
    }

    /** Without the marker file the reader reports itself unavailable instead of failing later. */
    @Test
    void reportsUnavailableWhenMarkerIsMissing(@TempDir Path empty) {
        ReportReader reader = ReportReader.of(empty);

        assertFalse(reader.isAvailable());
        assertTrue(reader.read("packets.json").isEmpty());
    }

    @Test
    void readsDirectoriesAndTrees() throws IOException {
        Files.writeString(root.resolve("registries.json"), "{}");
        Path components = Files.createDirectories(root.resolve("minecraft/components/item"));
        Files.writeString(components.resolve("oak_log.json"), "{\"components\":{}}");
        Files.writeString(components.resolve("stick.json"), "{\"components\":{}}");
        Files.createDirectories(root.resolve("minecraft/components/block"));

        ReportReader reader = ReportReader.of(root);

        Map<String, JsonElement> direct = reader.readDirectory("minecraft/components/item");
        assertEquals(2, direct.size());
        assertTrue(direct.containsKey("oak_log"));

        Map<String, JsonElement> tree = reader.readTree("minecraft/components");
        assertEquals(2, tree.size());
        assertTrue(tree.containsKey("item/stick"));

        assertTrue(reader.readDirectory("minecraft/components/block").isEmpty());
        assertTrue(reader.readTree("does/not/exist").isEmpty());
    }

    /** Reports are read repeatedly by different extractors, so the parse result is cached. */
    @Test
    void cachesParsedReports() throws IOException {
        Files.writeString(root.resolve("registries.json"), "{}");
        Files.writeString(root.resolve("packets.json"), "{\"play\":1}");

        ReportReader reader = ReportReader.of(root);
        JsonElement first = reader.read("packets.json").orElseThrow();
        Files.writeString(root.resolve("packets.json"), "{\"play\":2}");

        assertEquals(first, reader.read("packets.json").orElseThrow());
    }
}
