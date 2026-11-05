package com.terila.extractor;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport.Extraction;
import com.terila.extractor.core.ExtractionReport;
import com.terila.extractor.core.ExtractorVersion;
import com.terila.extractor.core.ExtractionRunner;
import net.minecraft.core.registries.BuiltInRegistries;
import com.terila.extractor.WireFormatTestSupport.RegistryWire;
import com.terila.extractor.WireFormatTestSupport.TagsWire;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.gamerules.GameRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.terila.extractor.TestSupport.document;
import static com.terila.extractor.TestSupport.entries;
import static com.terila.extractor.TestSupport.objects;
import static com.terila.extractor.TestSupport.read;
import static com.terila.extractor.TestSupport.size;
import static com.terila.extractor.TestSupport.sorted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the whole pipeline head-less once and verifies the output against the game's own data.
 *
 * <p>This is the test that would have caught every regression found while porting to 26.3: it fails
 * if an extractor is skipped, if a document is empty that should not be, if a registry lost its
 * payload, if an id table disagrees with the runtime, or if a single output file is not valid JSON.
 *
 * <p>The run itself lives in {@link TestSupport#extraction()} so the schema and golden tests assert
 * against the same bytes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtractionIntegrationTest {

    /** Documents that must exist and hold content for every run. */
    private static final List<String> REQUIRED_DOCUMENTS = List.of(
            "_meta.json",
            "_validation.json",
            "advancements/minecraft.json",
            "advancements/minecraft.nbt",
            "assets/manifest.json",
            "blocks/block_entities.json",
            "blocks/minecraft.json",
            "blocks/materials.json",
            "blocks/properties.json",
            "blocks/shapes.json",
            "blocks/sounds.json",
            "blocks/state_ids.json",
            "commands/report.json",
            "commands/tree.nbt",
            "commands/tree.json",
            "damage_types/minecraft.json",
            "enchantments/effects.json",
            "enchantments/minecraft.json",
            "entities/attributes.json",
            "entities/metadata_types.json",
            "entities/minecraft.json",
            "entities/poses.json",
            "functions/minecraft.json",
            "gameplay/game_rules.json",
            "gameplay/potions.json",
            "items/component_defaults.json",
            "items/components.json",
            "items/enchantable.json",
            "items/food.json",
            "items/minecraft.json",
            "items/tools.json",
            "loot/blocks.json",
            "loot/minecraft.json",
            "loot/minecraft.nbt",
            "misc/attributes.json",
            "misc/block_entity_types.json",
            "misc/command_argument_types.json",
            "misc/data_component_predicate_types.json",
            "misc/data_component_types.json",
            "misc/loot_condition_types.json",
            "misc/loot_function_types.json",
            "misc/position_source_types.json",
            "misc/sounds.json",
            "misc/stat_types.json",
            "packets/ids.json",
            "packets/structures.json",
            "predicates/minecraft.json",
            "protocol/feature_flags.json",
            "protocol/json_rpc.json",
            "protocol/known_packs.json",
            "protocol/summary.json",
            "recipes/minecraft.json",
            "recipes/minecraft.nbt",
            "registries/datapack_formats.json",
            "registries/index.json",
            "registries/report.json",
            "structures/index.json",
            "tags/all.json",
            "tags/all.nbt",
            "tags/by_entry.json",
            "tags/client_tags.bin",
            "tags/blocks.json",
            "tags/effective_functions.json",
            "tags/entities.json",
            "tags/items.json",
            "translations/en_us.json",
            "translations/locales.json",
            "trims/materials.json",
            "trims/patterns.json",
            "worldgen/biome_parameters.json",
            "worldgen/biomes.json",
            "worldgen/density_functions.json",
            "worldgen/dimensions.json",
            "worldgen/features.json",
            "worldgen/multi_noise.json",
            "worldgen/noise_settings.json",
            "worldgen/placed_features.json",
            "worldgen/processor_lists.json",
            "worldgen/structure_sets.json",
            "worldgen/structures.json",
            "worldgen/template_pools.json"
    );

    /**
     * The only documents vanilla ships empty: no function sources, function tags or item modifiers
     * exist in the game. Each one says so explicitly rather than being a bare empty array.
     */
    private static final Set<String> EXPECTED_EMPTY_DOCUMENTS = Set.of(
            "functions/minecraft.json",
            "item_modifiers/minecraft.json",
            "tags/effective_functions.json",
            "tags/functions.json");

    private static Extraction fixture;
    private static Path output;
    private static ExtractionReport report;
    private static int reportPacketCount;

    @BeforeAll
    void extractEverything() {
        fixture = TestSupport.extraction();
        output = fixture.output();
        report = fixture.report();
        reportPacketCount = fixture.groundTruth().packets();
    }

    // --- run level -------------------------------------------------------------------------

    @Test
    void everyExtractorSucceeds() {
        List<String> failures = report.failures().stream()
                .map(result -> result.name() + ": " + result.error())
                .toList();

        assertTrue(failures.isEmpty(), "failing extractors: " + failures);
        assertTrue(report.successfulExtractors() >= 100,
                "expected the full extractor set, ran " + report.successfulExtractors());
        assertTrue(report.totalEntries() > 40_000,
                "expected tens of thousands of entries, got " + report.totalEntries());
    }

    /** A clean run means no extractor raised an error, and warnings stay explainable. */
    @Test
    void noErrorsAreRecorded() {
        JsonArray recorded = TestSupport.errors().getAsJsonArray("errors");

        assertTrue(recorded.isEmpty(), "errors recorded: " + recorded);
    }

    @Test
    void runMetadataDescribesTheVersion() {
        JsonObject meta = document("_meta.json");

        assertEquals(ExtractorVersion.MINECRAFT_VERSION, meta.get("minecraft_version").getAsString());
        assertEquals(ExtractorVersion.PROTOCOL_VERSION, meta.get("protocol_version").getAsInt());
        assertEquals(ExtractorVersion.DATA_PACK_VERSION, meta.get("data_pack_version").getAsString());
        assertEquals(report.totalEntries(), meta.get("total_entries").getAsInt());
        assertTrue(meta.has("report_hash"));
    }

    /**
     * The run has to say whether the output was actually validated.
     *
     * <p>This is what stops the validation pass from being silently skipped: the report has to claim
     * it ran, and it has to have found the whole document set clean.
     */
    @Test
    void everyDocumentIsValidatedAgainstItsSchema() {
        JsonObject meta = document("_meta.json").getAsJsonObject("validation");
        assertTrue(meta.get("performed").getAsBoolean(), "the run did not validate its output");

        JsonObject validation = document(ExtractionRunner.VALIDATION_FILE);
        assertTrue(validation.get("performed").getAsBoolean());
        assertTrue(validation.getAsJsonObject("schemas").get("count").getAsInt() >= 25,
                "the schema table shrank unexpectedly");

        JsonObject summary = validation.getAsJsonObject("summary");
        assertEquals(0, summary.get("errors").getAsInt(), "schema validation found errors");
        assertEquals(0, summary.get("warnings").getAsInt(), "schema validation found warnings");
        assertTrue(summary.get("validated").getAsInt() > 2_500,
                "expected thousands of validated documents, got " + summary.get("validated"));
        assertEquals(summary.get("documents").getAsInt(), summary.get("validated").getAsInt());
    }

    // --- documents -------------------------------------------------------------------------

    @Test
    void everyExpectedDocumentExistsAndHasContent() {
        List<String> missing = new ArrayList<>();
        List<String> empty = new ArrayList<>();

        for (String relative : REQUIRED_DOCUMENTS) {
            Path file = output.resolve(relative);
            if (!Files.isRegularFile(file)) {
                missing.add(relative);
            } else if (size(file) < 128) {
                empty.add(relative + " (" + size(file) + " bytes)");
            }
        }

        assertTrue(missing.isEmpty(), "missing documents: " + missing);
        assertTrue(empty.isEmpty(), "documents without content: " + empty);
    }

    /**
     * Only the two datasets vanilla genuinely has no content for may be empty, and both have to say
     * so explicitly — otherwise an empty array is indistinguishable from a failed extraction.
     */
    @Test
    void emptyDocumentsAreTheOnesVanillaHasNoDataFor() throws IOException {
        List<String> emptyDocuments = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(output, 2)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                JsonElement parsed = read(file);
                if (!parsed.isJsonObject()) {
                    continue;
                }
                JsonObject document = parsed.getAsJsonObject();
                JsonElement data = document.get("data");
                if (data != null && data.isJsonArray() && data.getAsJsonArray().isEmpty()) {
                    emptyDocuments.add(output.relativize(file).toString().replace('\\', '/'));
                }
            }
        }

        assertEquals(new TreeSet<>(EXPECTED_EMPTY_DOCUMENTS), new TreeSet<>(emptyDocuments),
                "unexpected empty documents: " + emptyDocuments);

        for (String relative : EXPECTED_EMPTY_DOCUMENTS) {
            JsonObject document = document(relative);
            assertFalse(document.get("vanilla_provides").getAsBoolean(),
                    relative + " claims to have vanilla data but is empty");
            assertTrue(document.has("note"), relative + " must explain why it is empty");
        }

        // The item modifier document still carries the vocabulary the format is built from.
        assertTrue(document("item_modifiers/minecraft.json").get("type_count").getAsInt() > 20);

        // Function sources are empty in vanilla, but the document names the directory a pack may use.
        JsonObject functions = document("functions/minecraft.json");
        assertEquals(0, functions.get("function_count").getAsInt());
        assertEquals(0, functions.get("total_command_count").getAsInt());
    }

    /** Every emitted file, at any depth, has to be parseable JSON. */
    @Test
    void everyOutputFileIsValidJson() {
        List<String> broken = new ArrayList<>();
        List<Path> files = TestSupport.jsonFiles(output);

        for (Path file : files) {
            try {
                read(file);
            } catch (RuntimeException failure) {
                broken.add(output.relativize(file) + ": " + failure.getMessage());
            }
        }

        assertTrue(broken.isEmpty(), "invalid JSON: " + broken);
        assertTrue(files.size() > 5_000, "expected thousands of output files, checked " + files.size());
    }

    // --- blocks ----------------------------------------------------------------------------

    @Test
    void blockStateIdTableMatchesTheRuntime() {
        JsonObject document = document("blocks/state_ids.json");

        assertEquals(0, document.get("runtime_id_mismatches").getAsInt(),
                "the report and the runtime disagree about block state ids");
        assertEquals(BuiltInRegistries.BLOCK.size(), document.get("block_count").getAsInt());
        assertEquals(fixture.groundTruth().blockStates(), document.get("state_count").getAsInt());
        assertEquals(fixture.groundTruth().highestStateId(), document.get("highest_state_id").getAsInt());

        // Deterministic order and one file per block.
        List<String> ids = new ArrayList<>();
        for (JsonElement element : document.getAsJsonArray("data")) {
            ids.add(element.getAsJsonObject().get("id").getAsString());
        }
        assertEquals(sorted(ids), ids, "block state ids are not sorted");
    }

    @Test
    void oneBlockStateFilePerBlock() throws IOException {
        Path directory = output.resolve("blocks/blockstates");
        assertTrue(Files.isDirectory(directory));

        try (Stream<Path> stream = Files.list(directory)) {
            assertEquals(BuiltInRegistries.BLOCK.size(), stream.count());
        }

        JsonObject sample = document("blocks/blockstates/minecraft_stone.json");
        assertFalse(sample.getAsJsonArray("states").isEmpty());
        assertEquals("minecraft:stone", sample.get("block_id").getAsString());
    }

    /** Every block entity type has to be resolvable from the block that creates it. */
    @Test
    void blockEntityMappingsCoverEveryTypeAndKnownBlocks() {
        JsonObject document = document("blocks/block_entities.json");

        assertEquals(BuiltInRegistries.BLOCK_ENTITY_TYPE.size(),
                document.get("block_entity_type_count").getAsInt());
        assertTrue(document.get("mapping_count").getAsInt() > 100,
                "expected a useful block-to-block-entity table");

        boolean chest = false;
        List<String> duplicateKeys = new ArrayList<>();
        for (JsonObject mapping : objects(document.getAsJsonArray("data"))) {
            String block = mapping.get("block").getAsString();
            String type = mapping.get("block_entity_type").getAsString();
            assertTrue(BuiltInRegistries.BLOCK.containsKey(Identifier.parse(block)), block);
            assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(Identifier.parse(type)), type);
            if (block.equals("minecraft:chest") && type.equals("minecraft:chest")) {
                chest = true;
            }
            duplicateKeys.add(block + " -> " + type);
        }

        assertTrue(chest, "the chest block has no chest block entity mapping");
        assertEquals(duplicateKeys.size(), new TreeSet<>(duplicateKeys).size(),
                "the block entity mapping contains duplicate pairs");
    }

    // --- registries ------------------------------------------------------------------------

    @Test
    void registryManifestCoversEveryBuiltInRegistry() {
        JsonObject index = document("registries/index.json");
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement element : index.getAsJsonArray("index")) {
            JsonObject entry = element.getAsJsonObject();
            byId.put(entry.get("id").getAsString(), entry);
        }

        List<String> missing = new ArrayList<>();
        for (String id : TestSupport.builtInRegistryIds()) {
            if (!byId.containsKey(id)) {
                missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(), "registries missing from the manifest: " + missing);

        assertTrue(byId.size() >= 150, "expected every registry including the data pack ones, got " + byId.size());
        assertTrue(index.get("datapack_registry_count").getAsInt() >= 55);

        // Every manifest entry points at a file that exists and agrees on the entry count.
        for (JsonObject entry : byId.values()) {
            Path dump = output.resolve(entry.get("dump").getAsString());
            assertTrue(Files.isRegularFile(dump), "missing dump for " + entry.get("id").getAsString());
            JsonObject registry = document(entry.get("dump").getAsString()).getAsJsonObject("registries");
            assertEquals(entry.get("entry_count").getAsInt(), registry.get("entry_count").getAsInt(),
                    "entry count mismatch for " + entry.get("id").getAsString());
        }

        List<String> ids = new ArrayList<>(byId.keySet());
        assertEquals(sorted(ids), ids, "the manifest is not sorted");
    }

    /**
     * Data pack registries have to carry the payload, not just the name.
     *
     * <p>One registry, {@code minecraft:block_transformer}, is defined entirely by its name, so its
     * entries legitimately encode as a string. The assertion is therefore "every data pack registry
     * has a payload", with the document shape checked on the registries that carry fields.
     */
    @Test
    void datapackRegistriesCarryTheirPayload() {
        JsonObject index = document("registries/index.json");
        int checked = 0;

        for (JsonObject entry : objects(index.getAsJsonArray("index"))) {
            if (!"datapack".equals(entry.get("source").getAsString())
                    || entry.get("entry_count").getAsInt() == 0) {
                continue;
            }
            JsonObject dump = document(entry.get("dump").getAsString()).getAsJsonObject("registries");
            JsonObject first = dump.getAsJsonArray("entries").get(0).getAsJsonObject();

            assertTrue(first.has("data"),
                    "registry " + entry.get("id").getAsString() + " lost its payload");
            checked++;
        }

        assertTrue(checked >= 50, "expected every loaded data pack registry to be dumped, checked " + checked);

        // The registries an engine actually reads have to carry a structured document.
        for (String id : List.of("minecraft:worldgen/biome", "minecraft:enchantment",
                "minecraft:recipe", "minecraft:loot_table", "minecraft:advancement",
                "minecraft:worldgen/structure", "minecraft:worldgen/feature",
                "minecraft:worldgen/template_pool", "minecraft:worldgen/placed_feature",
                "minecraft:damage_type", "minecraft:trim_material", "minecraft:dimension_type")) {
            JsonObject dump = document("registries/dump/" + id.replace(':', '/') + ".json")
                    .getAsJsonObject("registries");
            JsonObject first = dump.getAsJsonArray("entries").get(0).getAsJsonObject();
            assertTrue(first.has("data") && first.get("data").isJsonObject(),
                    "registry " + id + " encoded a reference instead of a document: " + first);
        }
    }

    /**
     * Every registry has to have a compressed NBT mirror, not only a JSON dump.
     *
     * <p>For a data pack registry the binary is what {@code ClientboundRegistryDataPacket} carries;
     * for a built-in registry it keeps the identity and protocol ids. Reading both back with the
     * game's own {@link NbtIo} proves the file is real NBT and that nothing became an empty compound.
     */
    @Test
    void everyRegistryHasBinaryNbt() throws IOException {
        JsonObject index = document("registries/index.json");
        int checked = 0;

        for (JsonObject entry : objects(index.getAsJsonArray("index"))) {
            String id = entry.get("id").getAsString();
            assertTrue(entry.has("nbt"), "no NBT path for " + id);
            Path file = output.resolve(entry.get("nbt").getAsString());
            assertTrue(Files.isRegularFile(file), "missing NBT for " + id);

            RegistryWire wire = WireFormatTestSupport.readRegistry(file);
            assertEquals(id, wire.registryId());
            assertEquals(entry.get("entry_count").getAsInt(), wire.entries().size());
            if ("datapack".equals(entry.get("source").getAsString())
                    && !wire.entries().isEmpty()) {
                assertTrue(wire.entries().get(0).data().isPresent(),
                        "the first NBT entry of " + id + " lost its payload");
            }
            checked++;
        }

        assertEquals(index.get("nbt_registry_count").getAsInt(), checked);
        assertTrue(checked >= 150, "expected every registry in NBT, got " + checked);
    }

    @Test
    void allTagBindingsHaveBinaryNbt() throws IOException {
        Path file = output.resolve("tags/all.nbt");
        assertTrue(Files.isRegularFile(file), "tags/all.nbt is missing");

        TagsWire wire = WireFormatTestSupport.readTags(file);
        JsonObject json = document("tags/all.json");
        Set<String> expected = new TreeSet<>();
        for (JsonObject group : TestSupport.objects(json.getAsJsonArray("data"))) {
            expected.add(group.get("registry").getAsString());
        }
        Set<String> actual = new TreeSet<>();
        for (var key : wire.tags().keySet()) {
            actual.add(key.identifier().toString());
        }

        assertEquals(expected, actual, "wire registry set must match tags/all.json");
        assertEquals(json.get("value_count").getAsInt(),
                WireFormatTestSupport.tagsEntryCount(wire));
        assertEquals(json.get("tag_count").getAsInt(),
                WireFormatTestSupport.tagsCount(wire),
                "empty tags belong in the wire payload");
        assertFalse(wire.tags().isEmpty(), "the wire tag document has no registry groups");
    }

    @Test
    void registryReportAndDatapackFormatsDescribeTheSurface() {
        JsonObject registryReport = document("registries/report.json");
        assertTrue(registryReport.get("registry_count").getAsInt() >= 90);
        assertTrue(registryReport.get("entry_count").getAsInt() > 5_000);

        JsonObject datapack = document("registries/datapack_formats.json");
        assertTrue(datapack.get("registry_count").getAsInt() >= 100);
        assertFalse(datapack.getAsJsonArray("element_registries").isEmpty());
        assertTrue(datapack.has("other_directories"));
    }

    /**
     * Every curated document that mirrors a whole registry has to agree with that registry's dump.
     *
     * <p>This is the audit that catches a document silently pointing at the wrong registry. It did:
     * 26.3 folded configured features into {@code minecraft:feature}, and {@code misc/feature_types}
     * was still reading that registry, so the “feature types” document held 240 configured features
     * and the real 58-entry type vocabulary appeared nowhere outside the registry dump. The same
     * check is applied to the 26.3 carver split.
     */
    @Test
    void curatedDocumentsMatchTheirRegistry() {
        Map<String, Integer> registryCounts = new LinkedHashMap<>();
        for (JsonObject entry : objects(document("registries/index.json").getAsJsonArray("index"))) {
            registryCounts.put(entry.get("id").getAsString(), entry.get("entry_count").getAsInt());
        }

        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("blocks/minecraft.json", "minecraft:block");
        documents.put("items/minecraft.json", "minecraft:item");
        documents.put("entities/minecraft.json", "minecraft:entity_type");
        documents.put("recipes/minecraft.json", "minecraft:recipe");
        documents.put("advancements/minecraft.json", "minecraft:advancement");
        documents.put("loot/minecraft.json", "minecraft:loot_table");
        documents.put("predicates/minecraft.json", "minecraft:predicate");
        documents.put("item_modifiers/minecraft.json", "minecraft:item_modifier");
        documents.put("enchantments/minecraft.json", "minecraft:enchantment");
        documents.put("damage_types/minecraft.json", "minecraft:damage_type");
        documents.put("trims/materials.json", "minecraft:trim_material");
        documents.put("trims/patterns.json", "minecraft:trim_pattern");
        documents.put("worldgen/biomes.json", "minecraft:worldgen/biome");
        documents.put("worldgen/features.json", "minecraft:worldgen/feature");
        documents.put("worldgen/placed_features.json", "minecraft:worldgen/placed_feature");
        documents.put("worldgen/structures.json", "minecraft:worldgen/structure");
        documents.put("worldgen/structure_sets.json", "minecraft:worldgen/structure_set");
        documents.put("worldgen/template_pools.json", "minecraft:worldgen/template_pool");
        documents.put("worldgen/processor_lists.json", "minecraft:worldgen/processor_list");
        documents.put("worldgen/noise_settings.json", "minecraft:worldgen/noise_settings");
        documents.put("worldgen/density_functions.json", "minecraft:worldgen/density_function");
        documents.put("worldgen/multi_noise.json",
                "minecraft:worldgen/multi_noise_biome_source_parameter_list");
        documents.put("misc/sounds.json", "minecraft:sound_event");
        documents.put("misc/particles.json", "minecraft:particle_type");
        documents.put("misc/mob_effects.json", "minecraft:mob_effect");
        documents.put("misc/fluids.json", "minecraft:fluid");
        documents.put("misc/game_events.json", "minecraft:game_event");
        documents.put("misc/menus.json", "minecraft:menu");
        documents.put("misc/attributes.json", "minecraft:attribute");
        documents.put("misc/block_entity_types.json", "minecraft:block_entity_type");
        documents.put("misc/stat_types.json", "minecraft:stat_type");
        documents.put("misc/position_source_types.json", "minecraft:position_source_type");
        documents.put("misc/command_argument_types.json", "minecraft:command_argument_type");
        documents.put("misc/data_component_predicate_types.json",
                "minecraft:data_component_predicate_type");
        documents.put("misc/data_component_types.json", "minecraft:data_component_type");
        documents.put("misc/feature_types.json", "minecraft:worldgen/feature_type");
        documents.put("misc/carvers.json", "minecraft:worldgen/carver");
        documents.put("misc/carver_types.json", "minecraft:worldgen/carver_type");
        documents.put("misc/structure_types.json", "minecraft:worldgen/structure_type");
        documents.put("misc/placement_modifiers.json", "minecraft:worldgen/placement_modifier_type");

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            int actual = document(entry.getKey()).getAsJsonObject("_meta")
                    .get("entry_count").getAsInt();
            Integer expected = registryCounts.get(entry.getValue());
            if (expected == null || expected != actual) {
                mismatches.add(entry.getKey() + " has " + actual + ", registry "
                        + entry.getValue() + " has " + expected);
            }
        }

        assertTrue(mismatches.isEmpty(), "curated documents disagree with their registry: "
                + mismatches);
    }

    /**
     * Every registry file and tag file of the loaded packs has to be represented in the output.
     *
     * <p>This is the census behind the completeness claim: the resource manager counts the source
     * files of every registry path and of {@code tags/}, then those counts are compared with the
     * registry dumps and {@code tags/all.json}. The data generator's own format report finally names
     * every directory the game can load, so a registry that stopped being dumped cannot hide behind a
     * valid but smaller manifest.
     */
    @Test
    void everyRegistryAndTagFileIsRepresentedInTheOutput() {
        var census = fixture.groundTruth().packCensus();
        List<String> mismatches = new ArrayList<>();
        Set<String> manifestIds = new TreeSet<>();

        for (JsonObject entry : objects(document("registries/index.json").getAsJsonArray("index"))) {
            String id = entry.get("id").getAsString();
            manifestIds.add(id);
            if (!"datapack".equals(entry.get("source").getAsString())) {
                continue;
            }

            int files = census.registryFiles().getOrDefault(id, 0);
            int entries = entry.get("entry_count").getAsInt();
            if (files != entries) {
                mismatches.add(id + ": " + files + " file(s) but " + entries + " entr(y/ies)");
            }
        }

        JsonObject formats = document("registries/datapack_formats.json");
        for (JsonElement element : formats.getAsJsonArray("element_registries")) {
            String id = element.getAsString();
            assertTrue(manifestIds.contains(id), "the format report names a registry with no dump: " + id);
        }
        assertTrue(formats.getAsJsonObject("other_directories").has("structure"));

        assertEquals(census.tagFiles(), document("tags/all.json").get("tag_count").getAsInt(),
                "tag files and the tag sweep disagree");
        assertEquals(census.functionFiles(), document("functions/minecraft.json")
                        .get("function_count").getAsInt(),
                "function files and the function sweep disagree");
        assertTrue(census.registryFiles().size() >= 50,
                "the census saw too few registry directories: " + census.registryFiles().size());
        assertTrue(mismatches.isEmpty(), "registry files and entries disagree: " + mismatches);
    }

    // --- cross-checks against the live game ------------------------------------------------

    @Test
    void recipesMatchTheLoadedRecipeManager() throws IOException {
        int runtimeRecipes = fixture.groundTruth().recipes();
        JsonObject recipes = document("recipes/minecraft.json");

        assertEquals(runtimeRecipes, recipes.getAsJsonObject("_meta").get("entry_count").getAsInt());
        assertTrue(runtimeRecipes > 1_000, "expected a full recipe set, got " + runtimeRecipes);

        // Grouped files must add up to the same total.
        int grouped = 0;
        try (Stream<Path> stream = Files.list(output.resolve("recipes"))) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("minecraft.json"))
                    .toList()) {
                grouped += read(file).getAsJsonObject().getAsJsonObject("_meta")
                        .get("entry_count").getAsInt();
            }
        }
        assertEquals(runtimeRecipes, grouped);
    }

    @Test
    void structureTemplatesCoverTheWholeVanillaPack() throws IOException {
        int templates = fixture.groundTruth().structureTemplates();
        JsonObject index = document("structures/index.json");

        assertEquals(templates, index.get("template_count").getAsInt());
        assertTrue(templates > 1_000, "expected the whole template library");
        assertEquals(0, index.get("failed_templates").getAsInt());
        assertTrue(index.get("total_block_count").getAsLong() > 500_000);

        // Jigsaw blocks carry an orientation; counting by block id has to find the whole tree.
        int jigsaws = 0;
        for (JsonObject entry : objects(index.getAsJsonArray("templates"))) {
            jigsaws += entry.get("jigsaw_count").getAsInt();
        }
        assertEquals(4_272, jigsaws, "jigsaw blocks disappeared from the template index");

        Path directory = output.resolve("structures/templates");
        try (Stream<Path> stream = Files.walk(directory)) {
            assertEquals(templates, stream.filter(Files::isRegularFile).count());
        }

        JsonObject empty = document("structures/templates/minecraft/empty.json");
        assertTrue(empty.getAsJsonObject("size").has("x"));
    }

    /**
     * A multi-palette template has to keep every variant, not just the first.
     *
     * <p>All twenty shipwreck templates use the NBT {@code palettes} list; before the extractor read
     * it, their whole hull was written as air. The top level and every entry of {@code palettes} must
     * describe the same blocks with the variant-specific states, and the positions have to add up.
     */
    @Test
    void multiPaletteTemplatesKeepEveryVariant() {
        JsonObject template = document(
                "structures/templates/minecraft/shipwreck/rightsideup_full.json");

        assertEquals(8, template.get("palette_count").getAsInt());
        assertFalse(template.has("blocks_with_invalid_palette_index"),
                "shipwreck palette indices resolve now");
        assertFalse(template.getAsJsonArray("palette").isEmpty());
        assertTrue(template.getAsJsonArray("palette").toString().contains("minecraft:oak_planks"));

        JsonArray palettes = template.getAsJsonArray("palettes");
        assertEquals(8, palettes.size());
        for (JsonElement element : palettes) {
            JsonObject palette = element.getAsJsonObject();
            assertFalse(palette.getAsJsonArray("palette").isEmpty());
            assertFalse(palette.getAsJsonObject("blocks").isEmpty());
            assertEquals(662, palette.get("block_count").getAsInt());

            int positions = 0;
            for (JsonElement block : palette.getAsJsonObject("blocks").asMap().values()) {
                positions += block.getAsJsonArray().size();
            }
            assertEquals(662, positions, "a palette lost block positions");
        }
    }

    /**
     * A template file has to be directly usable: positions, palette and block histogram.
     *
     * <p>The palette is validated against the live block registry. 26.3 renamed the palette NBT keys
     * from {@code {Name, Properties}} to {@code {id, properties}}; reading the old spelling silently
     * turned every entry into plain {@code minecraft:air}, which this check catches.
     */
    @Test
    void structureTemplateDocumentsAreUsable() {
        JsonObject template = document(
                "structures/templates/minecraft/village/plains/houses/plains_small_house_1.json");

        JsonArray palette = template.getAsJsonArray("palette");
        assertFalse(palette.isEmpty());
        assertTrue(template.get("block_count").getAsInt() > 0);
        assertTrue(template.get("distinct_block_states").getAsInt() > 0);

        // Every state must name a real block, and every property must exist on it.
        for (JsonElement element : palette) {
            String state = element.getAsString();
            String blockName = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockName));

            assertNotNull(block, "unknown block in palette: " + state);
            if (state.contains("[")) {
                String properties = state.substring(state.indexOf('[') + 1, state.length() - 1);
                for (String property : properties.split(",")) {
                    String name = property.substring(0, property.indexOf('='));
                    assertNotNull(block.getStateDefinition().getProperty(name),
                            "block " + blockName + " has no property " + name);
                }
            }
        }

        JsonObject blocks = template.getAsJsonObject("blocks");
        assertFalse(blocks.isEmpty());
        for (Map.Entry<String, JsonElement> state : blocks.entrySet()) {
            assertFalse(state.getValue().getAsJsonArray().isEmpty(),
                    "state " + state.getKey() + " has no positions");
            for (JsonElement position : state.getValue().getAsJsonArray()) {
                assertEquals(3, position.getAsJsonArray().size());
            }
        }
    }

    /**
     * The palette check, applied to every template in the pack rather than one sample.
     *
     * <p>Blocks are resolved through the registry, so a key rename or a serialisation slip anywhere
     * in the 1511 templates fails the test.
     */
    @Test
    void everyStructureTemplateOnlyReferencesRealBlocks() throws IOException {
        Path directory = output.resolve("structures/templates");
        List<String> unknown = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        int checked = 0;
        long positions = 0;

        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                JsonObject template = read(file).getAsJsonObject();
                List<JsonObject> variants = new ArrayList<>();
                if (template.has("palettes")) {
                    for (JsonElement palette : template.getAsJsonArray("palettes")) {
                        variants.add(palette.getAsJsonObject());
                    }
                    assertEquals(template.get("palette_count").getAsInt(), variants.size(),
                            "palette_count disagrees with the palettes array of " + file);
                } else {
                    variants.add(template);
                }

                JsonObject size = template.getAsJsonObject("size");
                int sizeX = size.get("x").getAsInt();
                int sizeY = size.get("y").getAsInt();
                int sizeZ = size.get("z").getAsInt();
                int blockCount = template.get("block_count").getAsInt();

                for (JsonObject variant : variants) {
                    for (JsonElement element : variant.getAsJsonArray("palette")) {
                        String state = element.getAsString();
                        String blockName = state.contains("[")
                                ? state.substring(0, state.indexOf('[')) : state;
                        checked++;
                        if (BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockName)) == null) {
                            unknown.add(directory.relativize(file) + " -> " + state);
                        }
                    }

                    int variantPositions = 0;
                    for (Map.Entry<String, JsonElement> entry
                            : variant.getAsJsonObject("blocks").entrySet()) {
                        for (JsonElement position : entry.getValue().getAsJsonArray()) {
                            JsonArray coordinates = position.getAsJsonArray();
                            int x = coordinates.get(0).getAsInt();
                            int y = coordinates.get(1).getAsInt();
                            int z = coordinates.get(2).getAsInt();
                            variantPositions++;
                            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                                if (outside.size() < 20) {
                                    outside.add(directory.relativize(file) + " -> " + position);
                                }
                            }
                        }
                    }
                    assertEquals(blockCount, variantPositions,
                            "a palette of " + file + " lost or duplicated block positions");
                    positions += variantPositions;
                }
            }
        }

        assertTrue(unknown.isEmpty(), "palette entries without a block: " + unknown);
        assertTrue(outside.isEmpty(), "block positions outside the declared size: " + outside);
        assertTrue(checked > 20_000, "expected thousands of palette entries, checked " + checked);
        assertTrue(positions > 1_000_000, "expected the whole block volume, checked " + positions);
    }

    @Test
    void registryDumpsForItemsAndBlocksAgreeWithTheRuntime() {
        JsonObject blocks = document("registries/dump/minecraft/block.json").getAsJsonObject("registries");
        assertEquals(BuiltInRegistries.BLOCK.size(), blocks.get("entry_count").getAsInt());

        JsonObject items = document("registries/dump/minecraft/item.json").getAsJsonObject("registries");
        assertEquals(BuiltInRegistries.ITEM.size(), items.get("entry_count").getAsInt());
    }

    @Test
    void gameRulesMatchTheLiveRegistry() {
        JsonObject document = document("gameplay/game_rules.json");
        assertEquals(BuiltInRegistries.GAME_RULE.size(), document.get("rule_count").getAsInt());

        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonObject rule : objects(document.getAsJsonArray("data"))) {
            String id = rule.get("id").getAsString();
            byId.put(id, rule);

            GameRule<?> live = BuiltInRegistries.GAME_RULE.getValue(Identifier.parse(id));
            assertNotNull(live, "unknown game rule " + id);
            assertEquals(live.getDescriptionId(), rule.get("description_id").getAsString(), id);
            assertEquals(live.category().id().toString(), rule.get("category").getAsString(), id);
            assertEquals(live.gameRuleType().getSerializedName(), rule.get("type").getAsString(), id);
            assertEquals(live.valueClass().getSimpleName(), rule.get("value_class").getAsString(), id);
            assertEquals(serialisedDefault(live), rule.get("default_value_text").getAsString(), id);
            if (live.defaultValue() instanceof Boolean bool) {
                assertEquals(bool, rule.get("default_value").getAsBoolean(), id);
            } else {
                assertEquals(((Number) live.defaultValue()).intValue(),
                        rule.get("default_value").getAsInt(), id);
            }
        }

        JsonObject keepInventory = byId.get("minecraft:keep_inventory");
        assertNotNull(keepInventory);
        assertEquals("boolean", keepInventory.get("type").getAsString());
        assertFalse(keepInventory.get("default_value").getAsBoolean());

        JsonObject randomTickSpeed = byId.get("minecraft:random_tick_speed");
        assertNotNull(randomTickSpeed);
        assertEquals("integer", randomTickSpeed.get("type").getAsString());
        assertEquals(3, randomTickSpeed.get("default_value").getAsInt());
    }

    @Test
    void potionsMatchTheirLiveEffectLists() {
        JsonObject document = document("gameplay/potions.json");
        assertEquals(BuiltInRegistries.POTION.size(), document.get("potion_count").getAsInt());

        Map<String, JsonObject> byId = new LinkedHashMap<>();
        int totalEffects = 0;
        for (JsonObject entry : objects(document.getAsJsonArray("data"))) {
            String id = entry.get("id").getAsString();
            byId.put(id, entry);

            Potion live = BuiltInRegistries.POTION.getValue(Identifier.parse(id));
            assertNotNull(live, "unknown potion " + id);
            assertEquals(live.name(), entry.get("name").getAsString(), id);
            assertEquals(live.hasInstantEffects(), entry.get("instant").getAsBoolean(), id);

            List<JsonObject> effects = objects(entry.getAsJsonArray("effects"));
            assertEquals(live.getEffects().size(), effects.size(), id);
            for (int i = 0; i < effects.size(); i++) {
                var expected = live.getEffects().get(i);
                JsonObject actual = effects.get(i);
                assertEquals(expected.getEffect().unwrapKey().orElseThrow().identifier().toString(),
                        actual.get("effect").getAsString(), id);
                assertEquals(expected.getDescriptionId(), actual.get("description_id").getAsString(), id);
                assertEquals(expected.getDuration(), actual.get("duration_ticks").getAsInt(), id);
                assertEquals(expected.getAmplifier(), actual.get("amplifier").getAsInt(), id);
                assertEquals(expected.isAmbient(), actual.get("ambient").getAsBoolean(), id);
                assertEquals(expected.isVisible(), actual.get("visible").getAsBoolean(), id);
                assertEquals(expected.showIcon(), actual.get("show_icon").getAsBoolean(), id);
            }
            totalEffects += effects.size();
        }

        assertEquals(totalEffects, document.get("total_effect_count").getAsInt());
        assertTrue(totalEffects > 40, "expected the full potion effect table, got " + totalEffects);

        JsonObject water = byId.get("minecraft:water");
        assertNotNull(water);
        assertEquals(0, water.get("effect_count").getAsInt());
        assertFalse(water.get("instant").getAsBoolean());

        JsonObject swiftness = byId.get("minecraft:swiftness");
        assertNotNull(swiftness);
        assertTrue(swiftness.getAsJsonArray("effects").toString().contains("minecraft:speed"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String serialisedDefault(GameRule<?> rule) {
        GameRule raw = rule;
        return raw.serialize(raw.defaultValue());
    }

    // --- reports ---------------------------------------------------------------------------

    @Test
    void packetIdsComeFromTheReport() {
        assumeTrue(fixture.reportsAvailable(), "data generator reports are not present");

        JsonObject packets = document("packets/ids.json");
        assertEquals(reportPacketCount, packets.getAsJsonArray("packets").size());
        assertTrue(reportPacketCount > 200, "expected the full packet table, got " + reportPacketCount);

        JsonObject summary = document("protocol/summary.json");
        assertEquals(reportPacketCount, summary.get("packet_count").getAsInt());
        assertEquals(ExtractorVersion.PROTOCOL_VERSION,
                summary.getAsJsonObject("versions").get("protocol").getAsInt());
        assertFalse(summary.getAsJsonArray("synchronized_registries").isEmpty());
    }

    @Test
    void protocolFeatureFlagsAndKnownPacksAreComplete() {
        JsonObject flags = document("protocol/feature_flags.json");
        assertTrue(flags.get("flag_count").getAsInt() >= 4);
        assertEquals(flags.get("flag_count").getAsInt(), flags.getAsJsonArray("all_flags").size());
        assertTrue(flags.getAsJsonArray("all_flags").toString().contains("minecraft:vanilla"));
        assertTrue(flags.getAsJsonArray("default_flags").toString().contains("minecraft:vanilla"));
        assertTrue(flags.getAsJsonArray("vanilla_flags").toString().contains("minecraft:vanilla"));

        JsonObject packs = document("protocol/known_packs.json");
        assertEquals(1, packs.get("known_pack_count").getAsInt());
        assertEquals(ExtractorVersion.DATA_PACK_VERSION, packs.get("data_pack_version").getAsString());
        JsonObject pack = packs.getAsJsonArray("known_packs").get(0).getAsJsonObject();
        assertEquals("minecraft", pack.get("namespace").getAsString());
        assertEquals("core", pack.get("id").getAsString());
        assertTrue(pack.get("vanilla").getAsBoolean());
        assertFalse(pack.get("version").getAsString().isBlank());
    }

    @Test
    void packetStructuresDescribeEveryPacketPayload() {
        assumeTrue(fixture.reportsAvailable(), "data generator reports are not present");

        JsonObject structures = document("packets/structures.json");
        assertEquals(reportPacketCount, structures.get("packet_count").getAsInt(),
                "every packet occurrence must have a structure candidate");
        assertEquals(reportPacketCount, structures.get("resolved_count").getAsInt());
        assertEquals(0, structures.get("unresolved_count").getAsInt());
        assertEquals(1, structures.get("codec_missing_count").getAsInt(),
                "only the unit bundle-delimiter packet should have no codec");
        assertEquals(5, structures.get("state_count").getAsInt());
        assertTrue(structures.get("structure_count").getAsInt() > 200);
        assertTrue(structures.get("class_count").getAsInt() > 200);
        assertTrue(structures.get("field_count").getAsInt() > 500);
        assertTrue(structures.get("known_codec_count").getAsInt() > 20);
        assertTrue(structures.get("format_note").getAsString().contains("codec_constant"));

        Map<String, JsonObject> byName = new LinkedHashMap<>();
        for (JsonObject structure : objects(structures.getAsJsonArray("data"))) {
            byName.put(structure.get("name").getAsString() + "/"
                    + structure.get("direction").getAsString(), structure);
        }

        JsonObject login = byName.get("minecraft:login/clientbound");
        assertNotNull(login);
        assertEquals("record", login.get("format").getAsString());
        assertEquals(12, login.get("field_count").getAsInt());
        assertEquals("composite", login.getAsJsonObject("codec").get("kind").getAsString());
        assertEquals(12, login.getAsJsonObject("codec").get("component_count").getAsInt());
        assertTrue(login.getAsJsonArray("fields").toString().contains("playerId"));
        assertTrue(login.getAsJsonArray("fields").toString().contains("levels"));

        JsonObject registryData = byName.get("minecraft:registry_data/clientbound");
        assertNotNull(registryData);
        Map<String, JsonObject> registryFields = new LinkedHashMap<>();
        for (JsonObject field : objects(registryData.getAsJsonArray("fields"))) {
            registryFields.put(field.get("name").getAsString(), field);
        }
        assertEquals("string", registryFields.get("registry").get("wire_type").getAsString());
        assertEquals("packet_spec",
                registryFields.get("registry").get("wire_type_source").getAsString());
        assertTrue(registryFields.get("registry").get("note").getAsString().contains("VarInt"));
        assertEquals("varint_prefixed_list",
                registryFields.get("entries").get("wire_type").getAsString());
        assertTrue(registryFields.get("entries").get("note").getAsString().contains("has_nbt"));

        JsonObject updateTags = byName.get("minecraft:update_tags/clientbound");
        assertNotNull(updateTags);
        JsonObject tagsField = objects(updateTags.getAsJsonArray("fields")).stream()
                .filter(field -> field.get("name").getAsString().equals("tags"))
                .findFirst().orElseThrow();
        assertEquals("varint_prefixed_map", tagsField.get("wire_type").getAsString());

        JsonObject addEntity = byName.get("minecraft:add_entity/clientbound");
        assertNotNull(addEntity);
        assertEquals("class", addEntity.get("format").getAsString());
        assertEquals(11, addEntity.get("field_count").getAsInt());
        assertEquals("custom", addEntity.getAsJsonObject("codec").get("kind").getAsString());

        JsonObject entityData = byName.get("minecraft:set_entity_data/clientbound");
        assertNotNull(entityData);
        assertTrue(entityData.getAsJsonArray("fields").toString().contains("packedItems"));

        List<JsonObject> customPayloads = objects(structures.getAsJsonArray("data")).stream()
                .filter(entry -> entry.get("name").getAsString().equals("minecraft:custom_payload")
                        && entry.get("direction").getAsString().equals("clientbound"))
                .toList();
        assertEquals(2, customPayloads.size(),
                "configuration and gameplay use different custom-payload codecs");
        int customPayloadStates = 0;
        for (JsonObject customPayload : customPayloads) {
            customPayloadStates += customPayload.getAsJsonArray("states").size();
        }
        assertEquals(2, customPayloadStates,
                "the common payload packet is accepted in configuration and play");

        JsonObject bundleDelimiter = byName.get("minecraft:bundle_delimiter/clientbound");
        assertNotNull(bundleDelimiter);
        assertEquals(0, bundleDelimiter.get("field_count").getAsInt());
        assertEquals("missing", bundleDelimiter.getAsJsonObject("codec").get("kind").getAsString());
    }

    @Test
    void itemComponentDefaultsCoverEveryItem() {
        assumeTrue(fixture.reportsAvailable(), "data generator reports are not present");

        JsonObject defaults = document("items/component_defaults.json");
        assertEquals(BuiltInRegistries.ITEM.size(), defaults.get("item_count").getAsInt());
        assertTrue(defaults.get("component_assignments").getAsInt() > 10_000);
        assertTrue(defaults.getAsJsonObject("component_usage").has("minecraft:item_name"));

        // Every item the report mentions has to exist in the game.
        for (String itemId : defaults.getAsJsonObject("data").keySet()) {
            assertNotNull(BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId)), itemId);
        }
    }

    @Test
    void jsonRpcSurfaceIsExtracted() {
        assumeTrue(fixture.reportsAvailable(), "data generator reports are not present");

        JsonObject rpc = document("protocol/json_rpc.json");
        assertTrue(rpc.get("incoming_method_count").getAsInt() > 0);
        assertTrue(rpc.get("outgoing_method_count").getAsInt() > 0);
        assertTrue(rpc.has("schema"));
    }

    // --- assets ----------------------------------------------------------------------------

    @Test
    void clientAssetsAreMirroredAndIndexed() throws IOException {
        assumeTrue(fixture.clientJarAvailable(), "client jar is not present");

        JsonObject index = document("assets/index.json");
        int assets = index.get("asset_count").getAsInt();
        int mirrored = index.get("json_assets_mirrored").getAsInt();
        int listed = index.get("binary_assets_listed").getAsInt();

        assertTrue(assets > 10_000, "expected the whole resource pack, got " + assets);
        assertTrue(mirrored > 5_000, "expected every JSON asset mirrored, got " + mirrored);
        assertTrue(listed > 3_000, "expected every binary asset listed, got " + listed);
        assertEquals(assets, mirrored + listed);
        // Binary assets are indexed, not copied, unless the run asked for a resource pack directory.
        assertEquals(0, index.get("binary_assets_copied").getAsInt());

        long onDisk;
        try (Stream<Path> stream = Files.walk(output.resolve("assets"))) {
            onDisk = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .count();
        }
        // Everything mirrored, plus the index and the manifest.
        assertEquals(mirrored + 2, onDisk);
    }

    /** The manifest has to describe the binary half of the pack reproducibly. */
    @Test
    void assetManifestHashesEveryBinaryAsset() {
        assumeTrue(fixture.clientJarAvailable(), "client jar is not present");

        JsonObject index = document("assets/index.json");
        JsonObject manifest = document("assets/manifest.json");

        assertEquals(index.get("binary_assets_listed").getAsInt(),
                manifest.get("binary_asset_count").getAsInt());
        assertTrue(manifest.get("distinct_content_hashes").getAsInt() > 1_000);
        assertTrue(manifest.getAsJsonArray("content_hashes").size()
                        <= manifest.get("binary_asset_count").getAsInt(),
                "distinct hashes cannot exceed the number of hashed files");

        for (JsonElement hash : manifest.getAsJsonArray("content_hashes")) {
            assertTrue(hash.getAsString().startsWith("sha256:"), "hash is not sha256: " + hash);
        }
    }

    /**
     * The manifest has to describe the locales that were actually read, and the English document has
     * to be the same table the jar carries.
     *
     * <p>The client jar ships English only — the other locales are launcher assets — so the number of
     * locales depends on whether {@code ./gradlew downloadLanguages} has run. Either way each manifest
     * entry must agree with its document, and there must be no locale the documents do not cover.
     */
    @Test
    void translationsCoverEveryLocaleInTheJar() {
        assumeTrue(fixture.clientJarAvailable(), "client jar is not present");

        JsonObject manifest = document("translations/locales.json");
        JsonObject locales = manifest.getAsJsonObject("locales");
        assertTrue(locales.size() >= 1);
        assertEquals("en_us", manifest.get("fallback_locale").getAsString());

        JsonObject english = document("translations/en_us.json");
        int englishKeys = english.getAsJsonObject("data").size();
        assertTrue(englishKeys > 5_000, "the jar carries the full English table, got " + englishKeys);
        assertEquals(englishKeys, locales.getAsJsonObject("en_us").get("keys").getAsInt(),
                "the manifest disagrees with translations/en_us.json");
        assertTrue(english.getAsJsonObject("data").has("block.minecraft.stone"));

        for (Map.Entry<String, JsonElement> locale : locales.entrySet()) {
            JsonObject entry = locale.getValue().getAsJsonObject();
            Path document = output.resolve("translations/locales/" + locale.getKey() + ".json");
            assertTrue(Files.isRegularFile(document),
                    locale.getKey() + " is in the manifest but has no document");
            assertEquals(entry.get("keys").getAsInt(),
                    TestSupport.object(document).getAsJsonObject("data").size(),
                    locale.getKey() + " key count disagrees with its document");
        }
    }

    /** Every registry entry the language keys reference has to exist in the game. */
    @Test
    void translationKeysAreResolvable() {
        assumeTrue(fixture.clientJarAvailable(), "client jar is not present");

        JsonObject keys = document("translations/keys.json");
        JsonObject data = keys.getAsJsonObject("data");

        assertTrue(data.size() > 1_500, "expected a key per block and item, got " + data.size());
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            Identifier id = Identifier.parse(entry.getKey());
            assertTrue(BuiltInRegistries.BLOCK.containsKey(id) || BuiltInRegistries.ITEM.containsKey(id),
                    "translation key for unknown block/item: " + entry.getKey());
        }
        assertTrue(keys.getAsJsonObject("missing").isEmpty(),
                "every referenced translation key should exist in en_us: "
                        + keys.getAsJsonObject("missing"));
    }

    // --- commands --------------------------------------------------------------------------

    /**
     * The command tree has to be ordered by name at every level.
     *
     * <p>Brigadier hands children back in registration order, which is not the same on every server
     * start. Writing that order straight to JSON made the document's object members move between two
     * runs of identical data; this walks the whole tree so any unsorted level fails explicitly.
     */
    @Test
    void commandTreeChildrenAreSortedAtEveryLevel() {
        JsonObject tree = document("commands/tree.json").getAsJsonObject("tree");
        List<String> unsorted = new ArrayList<>();
        findUnsortedChildren(tree, "root", unsorted);

        assertTrue(unsorted.isEmpty(), "command tree levels that are not sorted: " + unsorted);
    }

    private static void findUnsortedChildren(JsonObject node, String path, List<String> unsorted) {
        JsonObject children = node.getAsJsonObject("children");
        if (children == null) {
            return;
        }

        List<String> names = new ArrayList<>(children.keySet());
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(null);
        if (!names.equals(sorted)) {
            unsorted.add(path + " -> " + names);
        }

        for (Map.Entry<String, JsonElement> child : children.entrySet()) {
            findUnsortedChildren(child.getValue().getAsJsonObject(), path + "/" + child.getKey(),
                    unsorted);
        }
    }
}

