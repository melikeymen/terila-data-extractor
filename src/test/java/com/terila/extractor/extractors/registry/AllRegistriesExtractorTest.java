package com.terila.extractor.extractors.registry;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the registry dump: the path mapping and the manifest/payload contract.
 *
 * <p>This extractor is the completeness guarantee — it walks every registry the game has, which is
 * what makes a future Minecraft release covered without an edit. The failure it exists to prevent is
 * the silent one, so the interesting assertions are the negative ones: every registry the game
 * registers has to appear, and the data pack ones have to carry their payload rather than a name.
 */
class AllRegistriesExtractorTest {

    @Test
    void mapsRegistryIdentifiersToOutputPaths() {
        assertEquals("registries/dump/minecraft/worldgen/biome.json",
                AllRegistriesExtractor.dumpPath("minecraft:worldgen/biome"));
        assertEquals("registries/dump/minecraft/block.json",
                AllRegistriesExtractor.dumpPath("minecraft:block"));
        assertEquals("registries/dump/terila/custom/thing.json",
                AllRegistriesExtractor.dumpPath("terila:custom/thing"));
    }

    /** An identifier without a namespace is treated as {@code minecraft}. */
    @Test
    void treatsAnUnqualifiedIdentifierAsMinecraft() {
        assertEquals("registries/dump/minecraft/block.json", AllRegistriesExtractor.dumpPath("block"));
    }

    /** Every registry the game registers must be in the manifest, at any depth. */
    @Test
    void manifestCoversEveryRegistryInTheGame() {
        JsonObject index = TestSupport.document("registries/index.json");
        List<String> ids = TestSupport.objects(index.getAsJsonArray("index")).stream()
                .map(entry -> entry.get("id").getAsString())
                .toList();

        List<String> missing = TestSupport.builtInRegistryIds().stream()
                .filter(id -> !ids.contains(id))
                .toList();

        assertTrue(missing.isEmpty(), "registries missing from the dump: " + missing);
        assertEquals(ids.size(), ids.stream().distinct().count(), "duplicate manifest entries");
    }

    /**
     * Data pack registries vanilla ships no entries for.
     *
     * <p>Listing them explicitly turns "a registry is empty" into a decision rather than an oversight:
     * a new release that empties another one fails this test instead of quietly losing its data.
     *
     * <ul>
     *   <li>{@code minecraft:dimension} — dimensions of a saved world live in its {@code level.dat}, and
     *       vanilla ships the {@code dimension_type} registry rather than any dimension;</li>
     *   <li>{@code minecraft:item_modifier} — vanilla ships no item modifiers at all;</li>
     *   <li>{@code minecraft:slot_source} — a registry introduced in 26.3 that has no vanilla entries.</li>
     * </ul>
     */
    private static final Set<String> EMPTY_IN_VANILLA = Set.of(
            "minecraft:dimension", "minecraft:item_modifier", "minecraft:slot_source");

    /** A data pack registry has to be dumped with its payload, not just its name. */
    @Test
    void dataPackRegistriesCarryStructuredPayloads() {
        JsonObject index = TestSupport.document("registries/index.json");
        Set<String> empty = new TreeSet<>();
        int withPayload = 0;

        for (JsonObject entry : TestSupport.objects(index.getAsJsonArray("index"))) {
            if (!"datapack".equals(entry.get("source").getAsString())) {
                continue;
            }
            if (entry.get("entry_count").getAsInt() == 0) {
                empty.add(entry.get("id").getAsString());
                continue;
            }
            withPayload++;

            JsonObject entries = TestSupport.document(entry.get("dump").getAsString())
                    .getAsJsonObject("registries").getAsJsonArray("entries")
                    .get(0).getAsJsonObject();
            assertTrue(entries.has("data"),
                    entry.get("id").getAsString() + " lost its payload");
        }

        assertEquals(EMPTY_IN_VANILLA, empty,
                "the set of registries vanilla ships empty changed; confirm it is intentional");
        assertTrue(withPayload >= 50, "expected the full data pack surface, got " + withPayload);
        assertEquals(60, index.get("datapack_registry_count").getAsInt());
        assertTrue(index.get("builtin_registry_count").getAsInt() >= 90,
                "expected every built-in registry, got " + index.get("builtin_registry_count"));
    }

    /** Protocol ids are what a network layer needs, so at least one built-in registry must carry them. */
    @Test
    void builtInRegistriesRecordProtocolIds() {
        JsonObject block = TestSupport.document("registries/dump/minecraft/block.json")
                .getAsJsonObject("registries");

        assertEquals(0, block.get("min_protocol_id").getAsInt());
        assertTrue(block.get("max_protocol_id").getAsInt() > 0);
        assertEquals(block.get("entry_count").getAsInt(),
                block.getAsJsonArray("entries").size());

        for (var element : block.getAsJsonArray("entries")) {
            assertTrue(element.getAsJsonObject().has("id"), "every entry has an identifier");
        }
    }

    /**
     * The manifest is the index consumers read first, so the totals have to agree with the dumps and
     * the entries each dump claims to hold.
     */
    @Test
    void manifestTotalsAgreeWithTheDumps() {
        JsonObject index = TestSupport.document("registries/index.json");
        int counted = 0;

        for (JsonObject entry : TestSupport.objects(index.getAsJsonArray("index"))) {
            JsonObject dump = TestSupport.document(entry.get("dump").getAsString())
                    .getAsJsonObject("registries");
            assertEquals(entry.get("entry_count").getAsInt(), dump.get("entry_count").getAsInt(),
                    "entry count mismatch for " + entry.get("id").getAsString());
            assertEquals(dump.getAsJsonArray("entries").size(), dump.get("entry_count").getAsInt());
            counted += dump.get("entry_count").getAsInt();
        }

        assertEquals(index.get("entry_count").getAsInt(), counted,
                "the manifest total must be the sum of the dumps");
    }

    /** Nothing may be dumped twice, which the manifest would hide if it were not checked. */
    @Test
    void everyRegistryIsDumpedOnce() {
        JsonObject index = TestSupport.document("registries/index.json");
        List<String> dumps = TestSupport.objects(index.getAsJsonArray("index")).stream()
                .map(entry -> entry.get("dump").getAsString())
                .toList();

        assertEquals(dumps.size(), dumps.stream().distinct().count(), "a registry was dumped twice");
        assertFalse(dumps.isEmpty());
    }
}
