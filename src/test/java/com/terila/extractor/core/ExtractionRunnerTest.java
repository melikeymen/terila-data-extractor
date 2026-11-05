package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terila.extractor.TestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the run-level cleanup without loading Minecraft data.
 *
 * <p>An empty registry is enough to exercise the part that matters here: the runner has to remove
 * the previous run before it validates, otherwise the validation pass counts documents this run did
 * not write. The extraction itself is covered by the integration fixture.
 */
class ExtractionRunnerTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void bootStrap() {
        // ExtractorVersion reads the detected game version; the runner needs it even when the
        // extractor registry is empty or deliberately cyclic.
        TestSupport.bootStrap();
    }

    @Test
    void aRunRemovesStaleDocumentsBeforeValidating() throws Exception {
        Path output = temp.resolve("out");
        write(output.resolve("blocks/stale.json"), "{}");
        write(output.resolve("_validation.json"), "{\"stale\":true}");
        write(output.resolve("notes.txt"), "keep");

        ExtractorConfig config = ExtractorConfig.builder(output).threadCount(1).build();
        ExtractionRunner.run(config, new ExtractorRegistry(), ExtractorContext.builder());

        assertFalse(Files.exists(output.resolve("blocks/stale.json")));
        assertTrue(Files.exists(output.resolve("notes.txt")),
                "a file outside the managed layout must survive");

        JsonObject validation = object(output.resolve(ExtractionRunner.VALIDATION_FILE));
        assertEquals(0, validation.getAsJsonObject("summary").get("documents").getAsInt(),
                "the validation pass must not see the previous run's report");
    }

    @Test
    void aRunCanKeepTheExistingTreeOnRequest() throws Exception {
        Path output = temp.resolve("out");
        write(output.resolve("blocks/stale.json"), "{}");

        ExtractorConfig config = ExtractorConfig.builder(output)
                .threadCount(1)
                .cleanOutput(false)
                .build();
        ExtractionRunner.run(config, new ExtractorRegistry(), ExtractorContext.builder());

        assertTrue(Files.exists(output.resolve("blocks/stale.json")),
                "--no-clean has to preserve an existing tree");

        JsonObject validation = object(output.resolve(ExtractionRunner.VALIDATION_FILE));
        assertTrue(validation.getAsJsonObject("summary").get("documents").getAsInt() >= 1,
                "without cleanup the stale document is part of the validation pass");
    }

    @Test
    void aLockedOutputRejectsTheRunBeforeStaging() throws Exception {
        Path output = temp.resolve("out");
        write(output.resolve("blocks/old.json"), "{\"old\":true}");
        write(output.resolve("_meta.json"), "{\"old\":true}");

        try (OutputLock lock = OutputLock.acquire(output)) {
            ExtractorConfig config = ExtractorConfig.builder(output).threadCount(1).build();
            OutputLockedException failure = assertThrows(OutputLockedException.class,
                    () -> ExtractionRunner.run(config, new ExtractorRegistry(),
                            ExtractorContext.builder()));
            assertEquals(lock.lockFile(), failure.lockFile());
        }

        assertTrue(Files.exists(output.resolve("blocks/old.json")));
        assertTrue(Files.exists(output.resolve("_meta.json")));
        try (Stream<Path> siblings = Files.list(temp)) {
            assertFalse(siblings.anyMatch(path -> path.getFileName().toString()
                            .startsWith(".terila-extractor-staging-")),
                    "a rejected run must not create a staging directory");
        }
    }

    @Test
    void aPipelineFailureLeavesThePreviousRunUntouched() throws Exception {
        Path output = temp.resolve("out");
        write(output.resolve("blocks/old.json"), "{\"old\":true}");
        write(output.resolve("_meta.json"), "{\"old\":true}");
        write(output.resolve("notes.txt"), "keep");

        ExtractorRegistry registry = new ExtractorRegistry()
                .register(new CyclicA())
                .register(new CyclicB());
        ExtractorConfig config = ExtractorConfig.builder(output).threadCount(1).build();

        assertThrows(IllegalStateException.class,
                () -> ExtractionRunner.run(config, registry, ExtractorContext.builder()));

        assertTrue(Files.exists(output.resolve("blocks/old.json")));
        assertTrue(Files.exists(output.resolve("_meta.json")));
        assertTrue(Files.exists(output.resolve("notes.txt")));
        assertFalse(Files.exists(output.resolve("blocks/cycle_a.json")));
        try (Stream<Path> siblings = Files.list(temp)) {
            assertFalse(siblings.anyMatch(path -> path.getFileName().toString()
                            .startsWith(".terila-extractor-staging-")),
                    "an aborted run must remove its staging directory");
        }
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private JsonObject object(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final class CyclicA extends AbstractExtractor {

        private CyclicA() {
            super("cycle_a", DataType.BLOCKS, "blocks/cycle_a.json");
        }

        @Override
        public Set<Class<? extends DataExtractor>> dependencies() {
            return Set.of(CyclicB.class);
        }

        @Override
        public int extract(ExtractorContext context) {
            return 0;
        }
    }

    private static final class CyclicB extends AbstractExtractor {

        private CyclicB() {
            super("cycle_b", DataType.BLOCKS, "blocks/cycle_b.json");
        }

        @Override
        public Set<Class<? extends DataExtractor>> dependencies() {
            return Set.of(CyclicA.class);
        }

        @Override
        public int extract(ExtractorContext context) {
            return 0;
        }
    }
}

