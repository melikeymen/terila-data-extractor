package com.terila.extractor.bootstrap;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import com.terila.extractor.core.NbtFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the head-less command line.
 *
 * <p>Pack order is part of the contract: {@code VanillaDataLoader} stacks the directories in list
 * order and later packs win, so the parser must preserve the order the caller gave. One integration
 * test drives the same runner the command line uses, without exiting the JVM.
 */
class StandaloneExtractorTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void bootStrap() {
        TestSupport.bootStrap();
    }

    @Test
    void repeatedDatapackOptionsKeepTheirStackOrder() {
        StandaloneExtractor.Options options = StandaloneExtractor.Options.parse(new String[]{
                "--datapack", "low", "--output", "out", "--datapack", "high"});

        assertEquals(List.of(Path.of("low"), Path.of("high")), options.datapacks);
        assertEquals(Path.of("out"), options.output);
    }

    @Test
    void nbtFormatDefaultsToWire() {
        StandaloneExtractor.Options options = StandaloneExtractor.Options.parse(new String[]{});
        assertEquals(NbtFormat.WIRE, options.nbtFormat);
    }

    @Test
    void nbtFormatAcceptsSeparateAndEqualsSyntax() {
        assertEquals(NbtFormat.RAW,
                StandaloneExtractor.Options.parse(new String[]{"--nbt-format", "raw"}).nbtFormat);
        assertEquals(NbtFormat.BOTH,
                StandaloneExtractor.Options.parse(new String[]{"--nbt-format=both"}).nbtFormat);
    }

    @Test
    void nbtFormatRejectsUnknownValues() {
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> StandaloneExtractor.Options.parse(
                        new String[]{"--nbt-format=json"}));
        assertEquals("--nbt-format must be wire, raw or both: json", invalid.getMessage());
    }

    @Test
    void noCleanOptionDisablesOutputCleanup() {
        StandaloneExtractor.Options options = StandaloneExtractor.Options.parse(
                new String[]{"--output", "out", "--no-clean"});

        assertFalse(options.clean);
    }

    @Test
    void waitForLockOptionAcceptsNonNegativeSecondsAndRejectsNegativeValues() {
        StandaloneExtractor.Options options = StandaloneExtractor.Options.parse(
                new String[]{"--wait-for-lock", "7"});
        assertEquals(Duration.ofSeconds(7), options.lockWait);

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> StandaloneExtractor.Options.parse(
                        new String[]{"--wait-for-lock", "-1"}));
        assertEquals("--wait-for-lock must be >= 0", invalid.getMessage());
    }

    @Test
    void aLockedOutputReturnsExitCodeFourWithoutCreatingTheOutput() throws Exception {
        Path output = temp.resolve("out");
        Path lockFile = output.getParent().resolve(
                ".terila-extractor-lock-" + output.getFileName());

        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            StandaloneExtractor.Options options = StandaloneExtractor.Options.parse(
                    new String[]{"--output", output.toString(), "--only", "FUNCTIONS"});

            assertEquals(4, StandaloneExtractor.run(options));
        }

        assertFalse(Files.exists(output),
                "a run rejected by the lock must not create the output directory");
    }

    @Test
    void datapackOptionRequiresAValue() {
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> StandaloneExtractor.Options.parse(new String[]{"--datapack"}));

        assertEquals("--datapack requires a value", invalid.getMessage());
    }

    @Test
    void stacksRepeatedDatapacksThroughTheHeadlessRunner() throws Exception {
        Path low = temp.resolve("low");
        Path high = temp.resolve("high");
        Path lowFunctions = low.resolve("data/demo/function");
        Path highFunctions = high.resolve("data/demo/function");
        Files.createDirectories(lowFunctions);
        Files.createDirectories(low.resolve("data/demo/tags/function"));
        Files.createDirectories(highFunctions);
        Files.createDirectories(high.resolve("data/demo/tags/function"));

        Files.writeString(lowFunctions.resolve("a.mcfunction"), "say low\n", StandardCharsets.UTF_8);
        Files.writeString(low.resolve("data/demo/tags/function/load.json"),
                "{\"values\":[\"demo:a\"]}\n", StandardCharsets.UTF_8);
        Files.writeString(highFunctions.resolve("b.mcfunction"), "say high\n", StandardCharsets.UTF_8);
        Files.writeString(highFunctions.resolve("entry.mcfunction"),
                "function #demo:load\n", StandardCharsets.UTF_8);
        Files.writeString(high.resolve("data/demo/tags/function/load.json"),
                "{\"values\":[\"demo:b\"]}\n", StandardCharsets.UTF_8);

        Path output = temp.resolve("out");
        StandaloneExtractor.Options options = StandaloneExtractor.Options.parse(new String[]{
                "--output", output.toString(), "--only", "FUNCTIONS",
                "--datapack", low.toString(), "--datapack", high.toString(),
                "--no-validate"});

        assertEquals(0, StandaloneExtractor.run(options));

        JsonObject document = TestSupport.object(output.resolve("functions/minecraft.json"));
        assertEquals(3, document.get("function_count").getAsInt());
        assertEquals(List.of("demo:a", "demo:b", "demo:entry"),
                TestSupport.entries(document).stream()
                        .map(entry -> entry.get("id").getAsString()).toList());
        assertEquals(1, document.get("tag_expansion_mismatch_count").getAsInt());

        JsonObject difference = document.getAsJsonArray("tag_expansion_differences")
                .get(0).getAsJsonObject();
        assertEquals("demo:load", difference.get("id").getAsString());
        assertEquals(1, difference.get("call_count").getAsInt());
        assertTrue(difference.getAsJsonArray("only_raw_functions").isEmpty());
        assertEquals(List.of("demo:a"),
                difference.getAsJsonArray("only_effective_functions").asList().stream()
                        .map(JsonElement::getAsString).toList());
    }
}

