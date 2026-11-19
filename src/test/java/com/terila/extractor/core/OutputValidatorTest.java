package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the validation pass itself, against documents written to a temporary directory.
 *
 * <p>The point of these tests is the negative direction. A validation pass that always reports
 * success is worse than none, because it makes a broken run look checked; so each test writes a
 * document that violates exactly one part of the output contract and asserts that the pass notices.
 */
class OutputValidatorTest {

    private static final ExtractorConfig CONFIG = ExtractorConfig.builder().build();

    @TempDir
    Path root;

    @Test
    void acceptsAWellFormedDocument() throws IOException {
        write("blocks/minecraft.json", "{\"_meta\": " + meta() + ", \"data\": []}");

        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.Summary summary = OutputValidator.validate(root, CONFIG, errors);

        assertEquals(1, summary.documents());
        assertEquals(1, summary.validated());
        assertEquals(0, summary.errors(), "unexpected errors: " + errors.issues());
        assertTrue(summary.isValid());
    }

    /** A missing metadata field is the quietest way a document can go wrong. */
    @Test
    void rejectsADocumentWhoseMetadataIsIncomplete() throws IOException {
        JsonObject meta = JsonParser.parseString(meta()).getAsJsonObject();
        meta.remove("entry_count");
        write("blocks/minecraft.json", "{\"_meta\": " + meta + ", \"data\": []}");

        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.Summary summary = OutputValidator.validate(root, CONFIG, errors);

        assertEquals(1, summary.errors());
        assertFalse(summary.isValid());
        assertEquals("entry_count", errors.errors().get(0).field(),
                "the missing field has to be named: " + errors.errors().get(0));
    }

    /** An envelope whose data member stopped being an array is the shape change consumers hit. */
    @Test
    void rejectsAnEnvelopeWhoseDataIsNotAnArray() throws IOException {
        write("worldgen/biomes.json", "{\"_meta\": " + meta() + ", \"data\": {\"minecraft:plains\": 1}}");

        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.Summary summary = OutputValidator.validate(root, CONFIG, errors);

        // The envelope schema rejects the type, and the structural check names it explicitly.
        assertEquals(2, summary.errors(), "issues: " + errors.issues());
        assertEquals(Set.of("SCHEMA_TYPE", "OUTPUT_DATA_NOT_ARRAY"), types(errors));
    }

    /**
     * The same data rule holds for a document with no path specific schema, which is what the generic
     * metadata contract is for.
     */
    @Test
    void rejectsAnObjectDataMemberEvenWithoutASpecificSchema() throws IOException {
        write("extra/handmade.json", "{\"_meta\": " + meta() + ", \"data\": {}}");

        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.Summary summary = OutputValidator.validate(root, CONFIG, errors);

        assertEquals(1, summary.validated(), "the metadata contract still applies");
        assertEquals(1, summary.errors());
        assertEquals("OUTPUT_DATA_NOT_ARRAY", errors.errors().get(0).type());
    }

    /** The four documented exceptions keep their keyed data member. */
    @Test
    void allowsAKeyedDataMemberOnTheDocumentedExceptions() throws IOException {
        write("translations/en_us.json", "{\"_meta\": " + meta()
                + ", \"data\": {\"block.minecraft.stone\": \"Stone\"}}");

        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.validate(root, CONFIG, errors);

        assertEquals(0, errors.errorCount(), "unexpected errors: " + errors.issues());
    }

    @Test
    void reportsMalformedJson() throws IOException {
        write("blocks/minecraft.json", "{not json");

        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.Summary summary = OutputValidator.validate(root, CONFIG, errors);

        assertEquals(1, summary.errors());
        assertEquals("OUTPUT_INVALID_JSON", errors.errors().get(0).type());
    }

    /** Mirrored resource pack assets are the game's format, so they are not even counted. */
    @Test
    void leavesMirroredAssetsOutOfThePass() throws IOException {
        write("assets/minecraft/models/block/stone.json", "{\"parent\": \"block/cube_all\"}");
        write("assets/index.json", "{\"_meta\": " + meta() + ", \"assets\": [], \"groups\": {},"
                + " \"asset_count\": 0, \"json_assets_mirrored\": 0, \"binary_assets_listed\": 0}");

        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.Summary summary = OutputValidator.validate(root, CONFIG, errors);

        assertEquals(1, summary.documents(), "only assets/index.json is ours");
        assertEquals(1, summary.validated());
        assertEquals(0, summary.errors(), "unexpected errors: " + errors.issues());
    }

    /** A document with neither a schema nor a metadata block cannot be checked, and is counted as such. */
    @Test
    void countsDocumentsWithNoContractToCheck() throws IOException {
        write("extra/raw.json", "{\"anything\": true}");
        write("blocks/minecraft.json", "{\"_meta\": " + meta() + ", \"data\": []}");

        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.Summary summary = OutputValidator.validate(root, CONFIG, errors);

        assertEquals(2, summary.documents());
        assertEquals(1, summary.validated());
        assertEquals(1, summary.unchecked());
        assertEquals(0, summary.errors());
    }

    @Test
    void reportsAnEmptyDirectoryAsNothingToDo() {
        ErrorCollector errors = new ErrorCollector(64);
        OutputValidator.Summary summary = OutputValidator.validate(root.resolve("missing"), CONFIG, errors);

        assertEquals(0, summary.documents());
        assertEquals(0, summary.validated());
        assertTrue(summary.isValid());
    }

    /** The counters describe the pass, not the whole run. */
    @Test
    void countsOnlyTheIssuesItRaised() throws IOException {
        write("blocks/minecraft.json", "{not json");
        ErrorCollector errors = new ErrorCollector(64);
        errors.error("PRE_EXISTING", "somewhere", null, null, "raised before validation");

        OutputValidator.Summary summary = OutputValidator.validate(root, CONFIG, errors);

        assertEquals(1, summary.errors(), "the pre-existing issue must not be counted");
        assertEquals(2, errors.errorCount());
    }

    private static Set<String> types(ErrorCollector errors) {
        return errors.errors().stream().map(ExtractionIssue::type).collect(Collectors.toSet());
    }

    private static String meta() {
        return """
                {"minecraft_version": "26.3", "protocol_version": 777,
                 "data_pack_version": "121.0", "world_version": 5023,
                 "generated_at": "2026-09-17T00:00:00Z", "source": "minecraft:vanilla",
                 "extractor_name": "test", "entry_count": 0}
                """;
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
