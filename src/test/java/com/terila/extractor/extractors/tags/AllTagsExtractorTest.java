package com.terila.extractor.extractors.tags;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the tag sweep: every bound tag of every registry has to appear in {@code tags/all.json}.
 *
 * <p>The curated extractors answer "what is in this tag?" for seven categories. This sweep exists so
 * the answer for every <em>other</em> category cannot quietly be "nothing": 26.3 binds tags for
 * enchantments, damage types, potions, instruments, banner patterns, painting variants, points of
 * interest, timelines, dialogs and the whole worldgen tree, none of which had a document before.
 *
 * <p>The first test compares the document with the tag bindings of the live data set captured while
 * the extraction fixture was built, so a tag silently dropped by the sweep fails here rather than
 * looking like a valid, smaller table. The second pins the categories that were missing, and the
 * third proves the sweep and the curated documents cannot disagree.
 */
class AllTagsExtractorTest {

    /**
     * Categories no curated tag extractor covers.
     *
     * <p>Listed explicitly so removing one from the game — or from the sweep — is a decision, not an
     * accident. {@code minecraft:worldgen/*} is represented by its biome and structure tags, the two
     * the world generation code reads directly.
     */
    private static final Set<String> UNCOVERED_CATEGORIES = new TreeSet<>(List.of(
            "minecraft:banner_pattern",
            "minecraft:damage_type",
            "minecraft:dialog",
            "minecraft:enchantment",
            "minecraft:instrument",
            "minecraft:painting_variant",
            "minecraft:point_of_interest_type",
            "minecraft:potion",
            "minecraft:timeline",
            "minecraft:worldgen/biome",
            "minecraft:worldgen/structure"
    ));

    /**
     * Tags vanilla declares with no entries.
     *
     * <p>An empty tag is real data — it says nothing matches — so it must not be dropped from the
     * sweep. Pinning the exact set means a tag that loses its entries fails the test, and a new empty
     * tag in a later release is a reviewed change instead of a silent one.
     */
    private static final Set<String> EMPTY_TAGS = new TreeSet<>(List.of(
            "minecraft:block/default_immune_to",
            "minecraft:block/incorrect_for_diamond_tool",
            "minecraft:block/incorrect_for_netherite_tool",
            "minecraft:block/supports_frogspawn",
            "minecraft:damage_type/bypasses_cooldown",
            "minecraft:dialog/pause_screen_additions",
            "minecraft:dialog/quick_actions",
            "minecraft:villager_trade/common_smith/level_3",
            "minecraft:villager_trade/common_smith/level_4",
            "minecraft:villager_trade/common_smith/level_5"
    ));

    @Test
    void coversEveryTagRegistryTheGameHasBound() {
        JsonObject document = TestSupport.document("tags/all.json");
        Map<String, Integer> actual = new TreeMap<>();

        for (JsonObject group : TestSupport.objects(document.getAsJsonArray("data"))) {
            actual.put(group.get("registry").getAsString(), group.get("tag_count").getAsInt());
        }

        Map<String, Integer> expected = new TreeMap<>(
                TestSupport.extraction().groundTruth().tagsByRegistry());
        assertEquals(new TreeMap<>(expected), actual,
                "the tag sweep disagrees with the game's own tag bindings");

        assertEquals(expected.values().stream().mapToInt(Integer::intValue).sum(),
                document.get("tag_count").getAsInt(), "the total must be the sum of the groups");
        assertEquals(expected.size(), document.get("registry_count").getAsInt());
        assertTrue(document.get("tag_count").getAsInt() > 500,
                "expected the full vanilla tag set, got " + document.get("tag_count"));
    }

    @Test
    void includesCategoriesNoCuratedExtractorCovers() {
        JsonObject document = TestSupport.document("tags/all.json");
        Set<String> registries = new TreeSet<>();

        for (JsonObject group : TestSupport.objects(document.getAsJsonArray("data"))) {
            registries.add(group.get("registry").getAsString());
        }

        List<String> missing = UNCOVERED_CATEGORIES.stream()
                .filter(category -> !registries.contains(category))
                .toList();
        assertTrue(missing.isEmpty(), "tag categories missing from the sweep: " + missing);
    }

    /**
     * The curated documents and the sweep read the same bindings, so they must agree.
     *
     * <p>This is the guard against the two drifting apart: a curated extractor that falls back to an
     * empty list, or a sweep that resolves references differently, would make the same tag mean two
     * things depending on which file a consumer opened.
     */
    @Test
    void curatedDocumentsAgreeWithTheSweep() {
        Map<String, String> curatedToRegistry = new LinkedHashMap<>();
        curatedToRegistry.put("tags/blocks.json", "minecraft:block");
        curatedToRegistry.put("tags/items.json", "minecraft:item");
        curatedToRegistry.put("tags/entities.json", "minecraft:entity_type");
        curatedToRegistry.put("tags/fluids.json", "minecraft:fluid");
        curatedToRegistry.put("tags/game_events.json", "minecraft:game_event");
        curatedToRegistry.put("tags/villager_trades.json", "minecraft:villager_trade");

        Map<String, Map<String, List<String>>> sweep = sweepByRegistry();

        for (Map.Entry<String, String> entry : curatedToRegistry.entrySet()) {
            Map<String, List<String>> expected = sweep.get(entry.getValue());
            assertTrue(expected != null && !expected.isEmpty(),
                    entry.getValue() + " has no tags in the sweep");

            Map<String, List<String>> curated = new TreeMap<>();
            for (JsonObject tag : TestSupport.entries(TestSupport.document(entry.getKey()))) {
                curated.put(tag.get("id").getAsString(), strings(tag.getAsJsonArray("values")));
            }
            assertEquals(expected, curated, entry.getKey() + " disagrees with tags/all.json");
        }
    }

    /** Every group is internally consistent and deterministically ordered. */
    @Test
    void everyGroupIsOrderedAndConsistent() {
        JsonObject document = TestSupport.document("tags/all.json");
        Set<String> emptyTags = new TreeSet<>();
        int countedTags = 0;
        int countedValues = 0;

        for (JsonObject group : TestSupport.objects(document.getAsJsonArray("data"))) {
            List<JsonObject> tags = TestSupport.objects(group.getAsJsonArray("tags"));
            List<String> ids = new ArrayList<>();

            assertEquals(group.get("tag_count").getAsInt(), tags.size(),
                    group.get("registry").getAsString() + " tag_count disagrees with its tags");

            int values = 0;
            for (JsonObject tag : tags) {
                ids.add(tag.get("id").getAsString());
                List<String> entries = strings(tag.getAsJsonArray("values"));
                assertEquals(TestSupport.sorted(entries), entries,
                        tag.get("id").getAsString() + " values are not sorted");
                assertEquals(tag.get("value_count").getAsInt(), entries.size());
                if (entries.isEmpty()) {
                    emptyTags.add(group.get("registry").getAsString() + "/"
                            + tag.get("id").getAsString().substring("minecraft:".length()));
                }
                values += entries.size();
            }

            assertEquals(group.get("value_count").getAsInt(), values,
                    group.get("registry").getAsString() + " value_count disagrees with its tags");
            assertEquals(TestSupport.sorted(ids), ids,
                    group.get("registry").getAsString() + " tags are not sorted");

            countedTags += tags.size();
            countedValues += values;
        }

        assertEquals(EMPTY_TAGS, emptyTags, "the set of empty tags changed; confirm it is intentional");
        assertEquals(document.get("tag_count").getAsInt(), countedTags);
        assertEquals(document.get("value_count").getAsInt(), countedValues);
        assertTrue(countedValues > 5_000, "expected thousands of tag entries, got " + countedValues);
    }

    /** The inverse document must contain exactly the same memberships as the forward sweep. */
    @Test
    void byEntryIsTheExactInverseOfTheSweep() {
        Map<String, Map<String, TreeSet<String>>> expected = new TreeMap<>();
        for (JsonObject group : TestSupport.objects(
                TestSupport.document("tags/all.json").getAsJsonArray("data"))) {
            String registry = group.get("registry").getAsString();
            for (JsonObject tag : TestSupport.objects(group.getAsJsonArray("tags"))) {
                String tagId = tag.get("id").getAsString();
                for (String value : strings(tag.getAsJsonArray("values"))) {
                    expected.computeIfAbsent(registry, ignored -> new TreeMap<>())
                            .computeIfAbsent(value, ignored -> new TreeSet<>())
                            .add(tagId);
                }
            }
        }

        JsonObject document = TestSupport.document("tags/by_entry.json");
        Map<String, Map<String, TreeSet<String>>> actual = new TreeMap<>();
        int entryCount = 0;
        int membershipCount = 0;
        for (JsonObject group : TestSupport.objects(document.getAsJsonArray("data"))) {
            String registry = group.get("registry").getAsString();
            int memberships = 0;
            for (JsonObject entry : TestSupport.objects(group.getAsJsonArray("entries"))) {
                TreeSet<String> tags = new TreeSet<>(strings(entry.getAsJsonArray("tags")));
                actual.computeIfAbsent(registry, ignored -> new TreeMap<>())
                        .put(entry.get("id").getAsString(), tags);
                assertEquals(tags.size(), entry.get("tag_count").getAsInt());
                memberships += tags.size();
            }
            assertEquals(group.get("membership_count").getAsInt(), memberships);
            entryCount += group.get("entry_count").getAsInt();
            membershipCount += memberships;
        }

        assertEquals(expected, actual, "the inverse tag index disagrees with the sweep");
        assertEquals(entryCount, document.get("entry_count").getAsInt());
        assertEquals(membershipCount, document.get("membership_count").getAsInt());
        assertTrue(membershipCount > 5_000, "expected thousands of tag memberships");
    }

    /** Registry identifier to tag identifier to resolved values. */
    private static Map<String, Map<String, List<String>>> sweepByRegistry() {
        Map<String, Map<String, List<String>>> sweep = new TreeMap<>();

        for (JsonObject group : TestSupport.objects(
                TestSupport.document("tags/all.json").getAsJsonArray("data"))) {
            Map<String, List<String>> tags = new TreeMap<>();
            for (JsonObject tag : TestSupport.objects(group.getAsJsonArray("tags"))) {
                tags.put(tag.get("id").getAsString(), strings(tag.getAsJsonArray("values")));
            }
            sweep.put(group.get("registry").getAsString(), tags);
        }
        return sweep;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }
}

