package com.terila.extractor.extractors;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import net.minecraft.locale.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the display names in the catalogue documents.
 *
 * <p>Names come from the game's language table through {@code Component#getString()}, which memoises
 * inside the component instance without synchronisation. Resolving the same component from two
 * extractor threads at once used to return an <em>empty</em> string for a few percent of the
 * entries, silently (117 of 1658 items in one observed run). Every name is therefore cross-checked
 * against the language table here: a dropped or empty name fails immediately, and so does a name
 * that quietly fell back to the raw translation key.
 *
 * <p>The check needs the client jar (the language files only ship in it), so it is skipped when the
 * jar is missing rather than asserted away.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NameResolutionTest {

    /** Documents that carry a resolved {@code name} next to a {@code translation_key}. */
    private static final List<String> NAMED_DOCUMENTS = List.of(
            "blocks/minecraft.json",
            "entities/minecraft.json",
            "items/minecraft.json"
    );

    /**
     * Items whose name is built from data rather than from the description id alone: a potion is
     * "Water Bottle" or "Potion of Strength" depending on its {@code minecraft:potion_contents}, and
     * a tipped arrow is "Arrow of Poison". Their {@code item_name} component is the generic
     * translatable, so the name intentionally does not have to match it.
     */
    private static final String POTION_CONTENTS = "minecraft:potion_contents";

    @BeforeAll
    static void requireLanguageFiles() {
        assumeTrue(TestSupport.extraction().clientJarAvailable(),
                "the client jar (language files) is not available");
    }

    /** No catalogue entry may ship an empty display name. */
    @Test
    void everyEntryHasADisplayName() {
        for (String document : NAMED_DOCUMENTS) {
            List<String> unnamed = new ArrayList<>();
            for (JsonObject entry : TestSupport.entries(TestSupport.document(document))) {
                String name = entry.has("name") ? entry.get("name").getAsString() : null;
                if (name == null || name.isBlank()) {
                    unnamed.add(entry.get("id").getAsString() + " -> " + name);
                }
            }
            assertTrue(unnamed.isEmpty(),
                    document + " has " + unnamed.size() + " entry/entries without a display name: "
                            + unnamed.subList(0, Math.min(10, unnamed.size())));
        }
    }

    /** Blocks and entities resolve their name from their own translation key. */
    @Test
    void namesMatchTheLanguageTable() {
        List<String> mismatches = checkAgainstTranslationKey(
                "blocks/minecraft.json", "entities/minecraft.json");
        assertTrue(mismatches.isEmpty(),
                mismatches.size() + " name(s) disagree with the language table: "
                        + mismatches.subList(0, Math.min(10, mismatches.size())));
    }

    /**
     * Items resolve their name from the {@code minecraft:item_name} component (which may point at a
     * {@code block.minecraft.*} key), not from the description id of the item itself.
     */
    @Test
    void itemNamesMatchTheirItemNameComponent() {
        Language language = Language.getInstance();
        List<String> mismatches = new ArrayList<>();
        for (JsonObject entry : TestSupport.entries(TestSupport.document("items/minecraft.json"))) {
            if (hasComponent(entry, POTION_CONTENTS)) {
                continue;
            }
            String key = itemNameKey(entry);
            assertTrue(key != null,
                    "item " + entry.get("id") + " has no translatable minecraft:item_name component");
            String translated = language.getOrDefault(key, null);
            String name = entry.get("name").getAsString();
            if (translated == null) {
                assertEquals(key, name, "item " + entry.get("id"));
                continue;
            }
            if (translated.contains("%")) {
                assertFalse(name.contains("%"), "item " + entry.get("id") + " kept a placeholder: " + name);
                continue;
            }
            if (!translated.equals(name)) {
                mismatches.add("item " + entry.get("id") + " key=" + key
                        + " language=" + translated + " extracted=" + name);
            }
        }
        assertTrue(mismatches.isEmpty(),
                mismatches.size() + " item name(s) disagree with the language table: "
                        + mismatches.subList(0, Math.min(10, mismatches.size())));
    }

    /** A potion-style item must resolve the data-driven name instead of the generic one. */
    @Test
    void potionStyleItemsUseTheirComponentName() {
        Language language = Language.getInstance();
        List<String> potions = new ArrayList<>();
        for (JsonObject entry : TestSupport.entries(TestSupport.document("items/minecraft.json"))) {
            if (!hasComponent(entry, POTION_CONTENTS)) {
                continue;
            }
            potions.add(entry.get("id").getAsString());
            String generic = language.getOrDefault(itemNameKey(entry), "");
            String name = entry.get("name").getAsString();
            assertFalse(name.isBlank(), entry.get("id") + " has no name");
            assertFalse(name.equals(generic),
                    entry.get("id") + " kept the generic potion name " + name);
        }
        assertFalse(potions.isEmpty(), "no potion-style item was found at all");
    }

    /**
     * The resolution helper must be safe when many extractor threads share one component instance.
     *
     * <p>The game memoises the resolved text inside the component without synchronisation, so this
     * hammers a single cached component ({@code Blocks.STONE.getName()} is created once per block)
     * from several threads and insists that no thread ever observes the half-filled memo.
     */
    @Test
    void concurrentResolutionNeverReturnsEmptyText() throws Exception {
        TestSupport.bootStrap();
        var stone = net.minecraft.world.level.block.Blocks.STONE;
        String expected = com.terila.extractor.util.TextUtil.plain(stone.getName());
        assertFalse(expected.isBlank(), "the language table is empty: " + expected);

        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    int empty = 0;
                    for (int i = 0; i < 2_000; i++) {
                        if (com.terila.extractor.util.TextUtil.resolve(stone::getName).isEmpty()) {
                            empty++;
                        }
                    }
                    return empty;
                });
            }
            int empty = 0;
            for (var future : pool.invokeAll(tasks)) {
                empty += future.get();
            }
            assertEquals(0, empty, "empty text observed " + empty + " time(s)");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Every identifier must be unique within its document. */
    @Test
    void identifiersAreUnique() {
        for (String document : NAMED_DOCUMENTS) {
            List<JsonObject> entries = TestSupport.entries(TestSupport.document(document));
            Map<String, Long> counts = entries.stream()
                    .collect(Collectors.groupingBy(entry -> entry.get("id").getAsString(),
                            Collectors.counting()));
            List<String> duplicated = counts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .toList();
            assertTrue(duplicated.isEmpty(), document + " repeats " + duplicated);
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private static List<String> checkAgainstTranslationKey(String... documents) {
        Language language = Language.getInstance();
        List<String> mismatches = new ArrayList<>();
        for (String document : documents) {
            for (JsonObject entry : TestSupport.entries(TestSupport.document(document))) {
                String key = entry.get("translation_key").getAsString();
                String name = entry.get("name").getAsString();
                assertFalse(key.isBlank(), document + " entry " + entry.get("id") + " has no key");
                String translated = language.getOrDefault(key, null);
                if (translated == null) {
                    // Not in the language table at all: the key itself is the honest fallback.
                    assertEquals(key, name, document + " entry " + entry.get("id"));
                    continue;
                }
                if (translated.contains("%")) {
                    assertFalse(name.contains("%"), document + " " + entry.get("id") + ": " + name);
                    continue;
                }
                if (!translated.equals(name)) {
                    mismatches.add(document + " " + entry.get("id") + " key=" + key
                            + " language=" + translated + " extracted=" + name);
                }
            }
        }
        return mismatches;
    }

    private static boolean hasComponent(JsonObject item, String componentId) {
        JsonElement components = item.get("components");
        return components != null && components.isJsonObject()
                && components.getAsJsonObject().has(componentId);
    }

    /** The plain key of an item's {@code minecraft:item_name} component, or {@code null}. */
    private static String itemNameKey(JsonObject item) {
        JsonElement components = item.get("components");
        if (components == null || !components.isJsonObject()) {
            return null;
        }
        JsonElement name = components.getAsJsonObject().get("minecraft:item_name");
        if (name == null || !name.isJsonObject()) {
            return null;
        }
        JsonObject nameObject = name.getAsJsonObject();
        if (nameObject.has("with") || nameObject.has("extra")) {
            return null;
        }
        JsonElement key = nameObject.get("translate");
        return key == null ? null : key.getAsString();
    }
}
