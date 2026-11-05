package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the document schema table.
 *
 * <p>Two questions are worth answering without running a full extraction: does every path an
 * extractor writes resolve to a schema, and does the metadata contract actually reject a broken
 * metadata block? The first is a coverage check on the table, the second proves the generic schema is
 * not vacuous.
 */
class DocumentSchemasTest {

    /** Paths that exist in the output and must be covered, directly or by a prefix. */
    private static final List<String> SAMPLE_PATHS = List.of(
            "_meta.json",
            "_errors.json",
            "_validation.json",
            "blocks/minecraft.json",
            "blocks/blockstates/minecraft_stone.json",
            "blocks/state_ids.json",
            "blocks/block_entities.json",
            "items/minecraft.json",
            "items/component_defaults.json",
            "entities/minecraft.json",
            "entities/metadata_types.json",
            "recipes/minecraft.json",
            "tags/blocks.json",
            "tags/by_entry.json",
            "tags/effective_functions.json",
            "tags/functions.json",
            "worldgen/biomes.json",
            "worldgen/biome_parameters.json",
            "advancements/minecraft.json",
            "functions/minecraft.json",
            "gameplay/game_rules.json",
            "gameplay/potions.json",
            "loot/minecraft.json",
            "predicates/minecraft.json",
            "item_modifiers/minecraft.json",
            "registries/index.json",
            "registries/dump/minecraft/block.json",
            "registries/report.json",
            "registries/datapack_formats.json",
            "structures/index.json",
            "structures/templates/minecraft/empty.json",
            "packets/ids.json",
            "packets/structures.json",
            "protocol/summary.json",
            "protocol/feature_flags.json",
            "protocol/known_packs.json",
            "protocol/json_rpc.json",
            "commands/tree.json",
            "commands/report.json",
            "translations/en_us.json",
            "translations/keys.json",
            "translations/locales.json",
            "assets/index.json",
            "assets/manifest.json"
    );

    /** Every path in {@link #SAMPLE_PATHS} has to resolve, since each one really is written. */
    @Test
    void coversEveryDocumentPathTheExtractorsWrite() {
        List<String> uncovered = SAMPLE_PATHS.stream()
                .filter(path -> DocumentSchemas.forPath(path).isEmpty())
                .toList();

        assertTrue(uncovered.isEmpty(), "no schema for: " + uncovered);
        assertTrue(DocumentSchemas.exactPaths().size() >= 20, "the exact schema table shrank");
        assertTrue(DocumentSchemas.prefixes().contains("registries/dump/"));
        assertTrue(DocumentSchemas.prefixes().contains("structures/templates/"));
        assertTrue(DocumentSchemas.prefixes().contains("blocks/blockstates/"));
    }

    /** Raw resource pack assets are the game's format, not ours, so they are deliberately unchecked. */
    @Test
    void skipsMirroredAssetsButNotItsOwnTwoDocuments() {
        assertTrue(DocumentSchemas.isMirroredAsset("assets/minecraft/models/block/stone.json"));
        assertTrue(DocumentSchemas.isMirroredAsset("assets/minecraft/lang/en_us.json"));
        assertFalse(DocumentSchemas.isMirroredAsset("assets/index.json"));
        assertFalse(DocumentSchemas.isMirroredAsset("assets/manifest.json"));
        assertFalse(DocumentSchemas.isMirroredAsset("blocks/minecraft.json"));
    }

    /** The four documents whose data member is a key/value object rather than an array. */
    @Test
    void knowsWhichDocumentsMayCarryAnObjectDataMember() {
        assertTrue(DocumentSchemas.dataMayBeObject("translations/en_us.json"));
        assertTrue(DocumentSchemas.dataMayBeObject("items/component_defaults.json"));
        assertFalse(DocumentSchemas.dataMayBeObject("worldgen/biomes.json"));
        assertFalse(DocumentSchemas.dataMayBeObject("blocks/minecraft.json"));
    }

    @Test
    void metadataCarrierAcceptsTheCanonicalBlock() {
        assertTrue(validateMeta(validMeta()).isEmpty());
    }

    @Test
    void metadataCarrierRejectsAMissingField() {
        List<ExtractionIssue> issues = validateMeta(validMetaWithout("extractor_name"));

        assertEquals(1, issues.size(), "issues: " + issues);
        assertEquals("MISSING_FIELD", issues.get(0).type());
        assertEquals("extractor_name", issues.get(0).field());
    }

    /** A typo in a metadata field name has to be visible, not ignored. */
    @Test
    void metadataCarrierRejectsAnUnknownField() {
        List<ExtractionIssue> issues = validateMeta(validMeta().replace("\"minecraft_version\"",
                "\"minecraft_verison\""));

        assertEquals(2, issues.size(), "issues: " + issues);
        assertTrue(issues.stream().anyMatch(i -> i.type().equals("MISSING_FIELD")
                && i.field().equals("minecraft_version")),
                "the missing field has to be named: " + issues);
        assertTrue(issues.stream().anyMatch(i -> i.type().equals("UNKNOWN_PROPERTY")
                && i.field().equals("minecraft_verison")),
                "the unexpected field has to be named: " + issues);
    }

    @Test
    void metadataCarrierRejectsAWrongType() {
        List<ExtractionIssue> issues = validateMeta(validMeta().replace("\"entry_count\": 3",
                "\"entry_count\": \"three\""));

        assertEquals(1, issues.size(), "issues: " + issues);
        assertEquals("SCHEMA_TYPE", issues.get(0).type());
        assertEquals("$._meta.entry_count", issues.get(0).entry());
    }

    /** The envelope adds the data array on top of the metadata contract. */
    @Test
    void envelopeRequiresAnArrayDataMember() {
        JsonObject document = JsonParser.parseString(
                "{\"_meta\": " + validMeta() + ", \"data\": [{\"id\": \"minecraft:stone\"}]}")
                .getAsJsonObject();
        assertTrue(validate(document, DocumentSchemas.envelope()).isEmpty());

        JsonObject wrong = JsonParser.parseString("{\"_meta\": " + validMeta() + ", \"data\": {}}")
                .getAsJsonObject();
        assertEquals(1, validate(wrong, DocumentSchemas.envelope()).size());
    }

    /** Every schema in the table has to be checkable, i.e. produce issues on garbage. */
    @Test
    void everySchemaAcceptsAValidDocumentAndRejectsGarbage() {
        JsonElement garbage = JsonParser.parseString("\"not a document\"");
        for (String path : SAMPLE_PATHS) {
            Optional<JsonObject> schema = DocumentSchemas.forPath(path);
            assertTrue(schema.isPresent(), "no schema for " + path);
            assertFalse(validate(garbage, schema.get()).isEmpty(),
                    "schema for " + path + " accepts a string");
        }
    }

    /** Validates a document consisting only of the given metadata block. */
    private static List<ExtractionIssue> validateMeta(String metaJson) {
        JsonObject root = new JsonObject();
        root.add("_meta", JsonParser.parseString(metaJson).getAsJsonObject());
        return validate(root, DocumentSchemas.metaCarrier());
    }

    /** The canonical metadata block with one member dropped. */
    private static String validMetaWithout(String field) {
        JsonObject meta = JsonParser.parseString(validMeta()).getAsJsonObject();
        meta.remove(field);
        return meta.toString();
    }

    private static List<ExtractionIssue> validate(JsonElement document, JsonObject schema) {
        ErrorCollector errors = new ErrorCollector(64);
        ValidationContext context = new ValidationContext("subject", errors);
        new SchemaValidator(schema).validate(document, "subject", context);
        return context.issues();
    }

    private static String validMeta() {
        return """
                {"extractor_version": "1.0.0", "minecraft_version": "26.3", "protocol_version": 777,
                 "data_pack_version": "121.0", "world_version": 5023,
                 "generated_at": "2026-09-17T00:00:00Z", "source": "minecraft:vanilla",
                 "extractor_name": "blocks", "entry_count": 3}
                """;
    }
}

