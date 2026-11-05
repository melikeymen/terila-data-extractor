package com.terila.extractor.extractors.items;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import com.terila.extractor.bootstrap.VanillaDataLoader;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorLogger;
import com.terila.extractor.extractors.items.model.ItemData;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.DamageResistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests the item catalogue.
 *
 * <p>Two derived fields used to be produced by reflection and by searching the rendered JSON, both of
 * which fail silently: a renamed component payload method returned {@code -1} and a differently
 * encoded damage resistance tag reported "not fire resistant". The tests below pin the values against
 * the live component map so either regression shows up as a wrong number rather than a missing one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemExtractorTest {

    private Map<String, ItemData> catalog;

    @BeforeAll
    void buildCatalog() {
        TestSupport.bootStrap();
        ExtractorLogger.setVerbose(false);
        try (VanillaDataLoader data = VanillaDataLoader.load(null, ExtractorLogger.scoped("test"))) {
            ExtractorContext context = ExtractorContext.builder()
                    .config(ExtractorConfig.builder(Path.of("build", "test-output", "catalog"))
                            .threadCount(1).build())
                    .registryAccess(data.registryAccess())
                    .build();

            Registry<Item> registry = BuiltInRegistries.ITEM;
            Map<String, ItemData> byId = new LinkedHashMap<>();
            for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> entry
                    : com.terila.extractor.util.RegistryUtil.entries(registry)) {
                byId.put(entry.getKey().identifier().toString(),
                        ItemExtractor.describe(registry, entry.getKey(), entry.getValue(), context));
            }
            catalog = byId;
        }
    }

    @Test
    void coversEveryRegisteredItem() {
        assertEquals(BuiltInRegistries.ITEM.size(), catalog.size());
        assertTrue(catalog.size() > 1_500, "expected the full item registry");
    }

    /** Components have to be readable and serialisable, or every derived field is guesswork. */
    @Test
    void everyItemHasItsComponentsBound() {
        List<String> unbound = catalog.entrySet().stream()
                .filter(entry -> !entry.getValue().componentsBound())
                .map(Map.Entry::getKey)
                .toList();

        assertTrue(unbound.isEmpty(), "items without bound components: " + unbound);
    }

    @Test
    void derivesStackSizeAndDurability() {
        assertEquals(64, catalog.get("minecraft:stone").maxStackSize());
        assertEquals(1, catalog.get("minecraft:diamond_sword").maxStackSize());

        ItemData sword = catalog.get("minecraft:diamond_sword");
        assertTrue(sword.damageable());
        assertNotNull(sword.maxDamage());
        assertEquals(sword.maxDamage(), itemOf("minecraft:diamond_sword")
                .components().get(DataComponents.MAX_DAMAGE),
                "durability must be read from the item's own component map");
        assertFalse(catalog.get("minecraft:stone").damageable());
        assertNull(catalog.get("minecraft:stone").maxDamage(), "stone has no durability");
    }

    private static Item itemOf(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        assertNotNull(item, "unknown item " + id);
        return item;
    }


    @Test
    void identifiesFood() {
        assertTrue(catalog.get("minecraft:bread").food());
        assertTrue(catalog.get("minecraft:golden_apple").food());
        assertFalse(catalog.get("minecraft:stone").food());
    }

    /**
     * Fire resistance now comes from the generic damage resistance component.
     *
     * <p>26.2 replaced the boolean flag with {@code minecraft:damage_resistant}, whose payload is a
     * holder set rather than a tag name in a string, so the value is read structurally.
     */
    @Test
    void derivesFireResistanceFromTheDamageResistanceComponent() {
        assertTrue(catalog.get("minecraft:netherite_axe").fireResistant(), "netherite is fire proof");
        assertTrue(catalog.get("minecraft:ancient_debris").fireResistant());
        assertTrue(catalog.get("minecraft:netherite_ingot").fireResistant());

        assertFalse(catalog.get("minecraft:diamond_sword").fireResistant());
        assertFalse(catalog.get("minecraft:stone").fireResistant());
    }

    /** The component itself has to be present exactly on the items the flag is true for. */
    @Test
    void theFireResistantFlagMatchesTheComponent() {
        for (Map.Entry<String, ItemData> entry : catalog.entrySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.getKey()));
            assertNotNull(item, entry.getKey());

            DamageResistant component = item.components().get(DataComponents.DAMAGE_RESISTANT);
            boolean expected = ItemExtractor.isFireResistant(component);

            assertEquals(expected, entry.getValue().fireResistant(),
                    "fire resistance disagrees with the component for " + entry.getKey());
        }

        assertTrue(catalog.values().stream().filter(ItemData::fireResistant).count() > 10,
                "several items are fire resistant in vanilla");
    }

    /** A null payload means "not damage resistant", which must never be reported as fire proof. */
    @Test
    void treatsAnAbsentComponentAsNotFireResistant() {
        assertFalse(ItemExtractor.isFireResistant(null));
    }

    /** Every fire resistant item has to carry the component the flag is derived from. */
    @Test
    void everyFireResistantItemCarriesTheComponent() {
        List<String> without = catalog.values().stream()
                .filter(ItemData::fireResistant)
                .filter(data -> !data.components().containsKey("minecraft:damage_resistant"))
                .map(ItemData::id)
                .toList();

        assertTrue(without.isEmpty(), "fire resistant without the component: " + without);
    }

    @Test
    void derivesEnchantability() {
        assertEquals(15, catalog.get("minecraft:netherite_axe").enchantability());
        assertEquals(10, catalog.get("minecraft:diamond_sword").enchantability());
        assertEquals(1, catalog.get("minecraft:trident").enchantability());
        assertEquals(-1, catalog.get("minecraft:stone").enchantability(),
                "a block item is not enchantable");

        assertTrue(catalog.values().stream().filter(data -> data.enchantability() >= 0).count() > 50,
                "expected the full enchantable set");
    }

    /** Enchantability has to agree with the typed component accessor, not a reflective guess. */
    @Test
    void enchantabilityMatchesTheComponent() {
        for (Map.Entry<String, ItemData> entry : catalog.entrySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.getKey()));
            var enchantable = item.components().get(DataComponents.ENCHANTABLE);
            int expected = enchantable == null ? -1 : enchantable.value();

            assertEquals(expected, entry.getValue().enchantability(),
                    "enchantability disagrees with the component for " + entry.getKey());
        }
    }

    /** Rarity is the serialised form the codec uses, not the enum constant's Java name. */
    @Test
    void derivesRarity() {
        assertEquals("common", catalog.get("minecraft:stone").rarity());
        assertEquals("rare", catalog.get("minecraft:enchanted_book").rarity());

        for (ItemData data : catalog.values()) {
            assertTrue(List.of("common", "uncommon", "rare", "epic").contains(data.rarity()),
                    data.id() + " has an unexpected rarity: " + data.rarity());
        }
    }

    @Test
    void recordsTheFullComponentMap() {
        ItemData axe = catalog.get("minecraft:netherite_axe");

        assertEquals(axe.componentOrder().size(), axe.components().size(),
                "the order list and the map must describe the same components");
        assertEquals(axe.componentOrder().size(), axe.componentOrder().stream().distinct().count(),
                "component order must not repeat a component");
        assertTrue(axe.components().containsKey("minecraft:max_damage"));

        JsonObject json = axe.toJson();
        assertEquals(axe.componentOrder().size(), json.get("component_count").getAsInt());
        assertEquals(axe.components().keySet(), json.getAsJsonObject("components").keySet());
    }

    /**
     * A component that holds a registry reference must still carry it.
     *
     * <p>Every one of these payloads is a holder or a tag rather than a plain value: a block
     * transformer, an equipment asset, a jukebox song, a repair tag, tool rules. Encoding them without
     * a registry lookup fails, and the failure used to be written as a silent {@code null} — 265 other
     * components in this output were missing their payload that way.
     */
    @Test
    void registryBackedComponentsCarryTheirReference() {
        Map<String, JsonElement> axe = catalog.get("minecraft:copper_axe").components();
        assertEquals("minecraft:axe", axe.get("minecraft:block_transformer").getAsString());

        JsonObject repairable = axe.get("minecraft:repairable").getAsJsonObject();
        assertTrue(repairable.get("items").getAsString().startsWith("#"),
                "a repair list is a tag reference: " + repairable);

        JsonObject tool = axe.get("minecraft:tool").getAsJsonObject();
        assertFalse(tool.getAsJsonArray("rules").isEmpty(), "a tool component has harvest rules: " + tool);

        assertEquals("minecraft:11", catalog.get("minecraft:music_disc_11").components()
                .get("minecraft:jukebox_playable").getAsString());

        JsonObject resistant = catalog.get("minecraft:ancient_debris").components()
                .get("minecraft:damage_resistant").getAsJsonObject();
        assertEquals("#minecraft:is_fire", resistant.get("types").getAsString());

        JsonObject equippable = catalog.get("minecraft:black_carpet").components()
                .get("minecraft:equippable").getAsJsonObject();
        assertEquals("body", equippable.get("slot").getAsString());
        assertFalse(equippable.getAsJsonArray("allowed_entities").isEmpty());
    }

    /**
     * No component may serialise to {@code null}.
     *
     * <p>Components that are present on an item always have a payload; a {@code null} here means the
     * codec failed and the document lost the data, which is exactly the failure this test exists to
     * catch. (Sign text components do legitimately carry empty strings — those are values, not gaps.)
     */
    @Test
    void noComponentPayloadIsMissing() {
        Map<String, List<String>> hollow = new LinkedHashMap<>();
        for (ItemData data : catalog.values()) {
            List<String> nulls = data.components().entrySet().stream()
                    .filter(entry -> entry.getValue().isJsonNull())
                    .map(Map.Entry::getKey)
                    .toList();
            if (!nulls.isEmpty()) {
                hollow.put(data.id(), nulls);
            }
        }

        assertTrue(hollow.isEmpty(), "components without a payload: " + hollow);

        long payloads = catalog.values().stream()
                .flatMap(data -> data.components().values().stream())
                .filter(value -> !value.isJsonNull())
                .count();
        assertTrue(payloads > 10_000, "expected the whole component map, got " + payloads + " payloads");
    }

    /**
     * The extracted component map has to equal the game's own report of it.
     *
     * <p>{@code net.minecraft.data.Main} writes one file per item with the components that item
     * declares. Comparing against it turns "the payloads look right" into "the game serialised the
     * same bytes", which is what caught the missing registry lookup: with plain {@code JsonOps} every
     * holder-backed component differed.
     */
    @Test
    void componentMapMatchesTheGameReport() throws java.io.IOException {
        java.nio.file.Path reports = TestSupport.reportsRoot().orElse(null);
        assumeTrue(reports != null, "the data generator reports are not present");

        // Resolved exactly like the extractor does, so the test reads the same file it did.
        java.nio.file.Path directory = com.terila.extractor.core.ReportReader.of(reports)
                .path("minecraft/components/item");
        assumeTrue(java.nio.file.Files.isDirectory(directory),
                "the report has no per-item component directory: " + directory);

        List<String> mismatches = new java.util.ArrayList<>();
        int compared = 0;
        try (var files = java.nio.file.Files.list(directory)) {
            for (java.nio.file.Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                ItemData data = catalog.get("minecraft:" + name.substring(0, name.length() - ".json".length()));
                assertNotNull(data, "the report has an item the catalog lacks: " + name);

                JsonObject expected = TestSupport.object(file).getAsJsonObject("components");
                // Compared as serialised JSON, which is what a consumer reads: in memory a component
                // float is a Float and the report's value a double, so JsonElement equality would
                // report "2.4 != 2.4". Re-parsing our own output normalises the number types and still
                // fails on any real difference, including a missing member.
                JsonObject actual = com.google.gson.JsonParser
                        .parseString(data.toJson().getAsJsonObject("components").toString())
                        .getAsJsonObject();
                if (!expected.equals(actual)) {
                    mismatches.add(data.id() + " " + describeDifference(expected, actual));
                }
                compared++;
            }
        }

        assertTrue(mismatches.isEmpty(),
                mismatches.size() + " items disagree with the game's own component report, e.g. "
                        + mismatches.stream().limit(10).toList());
        assertTrue(compared > 1_500, "expected a report entry per item, compared " + compared);
    }

    /** First member that differs between the game's report and our document, for the failure message. */
    private static String describeDifference(JsonObject expected, JsonObject actual) {
        for (String key : expected.keySet()) {
            JsonElement mine = actual.get(key);
            if (!expected.get(key).equals(mine)) {
                return key + " report=" + expected.get(key) + " extracted=" + mine;
            }
        }
        for (String key : actual.keySet()) {
            if (!expected.has(key)) {
                return "extra " + key + "=" + actual.get(key);
            }
        }
        return "member order differs";
    }

    /** The translation key is the only name source an engine can rely on, so it must always be set. */
    @Test
    void everyItemHasATranslationKey() {
        for (ItemData data : catalog.values()) {
            assertNotNull(data.translationKey(), data.id() + " has no description id");
            assertTrue(data.translationKey().startsWith("item.minecraft.")
                            || data.translationKey().startsWith("block.minecraft."),
                    data.id() + " has an odd description id: " + data.translationKey());
        }
    }
}
