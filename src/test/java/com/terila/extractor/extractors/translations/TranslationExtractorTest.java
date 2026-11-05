package com.terila.extractor.extractors.translations;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import net.minecraft.locale.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the locale documents against the language files the extractor read.
 *
 * <p>The client jar only ships {@code en_us.json}; the other 140-odd locales come from
 * {@code ./gradlew downloadLanguages}. When that directory is missing the locale assertions are
 * skipped, but the English documents are always checked because the client jar is enough for them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TranslationExtractorTest {

    @BeforeAll
    static void requireOutput() {
        TestSupport.extraction();
    }

    /** English must cover every key the engine resolves for a block, item or entity. */
    @Test
    void englishCoversTheRegistryKeys() {
        JsonObject keys = TestSupport.document("translations/keys.json");
        JsonObject missing = keys.getAsJsonObject("missing");
        JsonObject data = keys.getAsJsonObject("data");
        assertFalse(data.isEmpty(), "no registry translation keys were collected");
        assertTrue(missing.size() < data.size() / 20,
                missing.size() + " of " + data.size() + " registry keys are missing from en_us");
    }

    /** The extracted English text must equal what the game itself resolves. */
    @Test
    void englishMatchesTheGameLanguage() {
        JsonObject data = TestSupport.document("translations/en_us.json").getAsJsonObject("data");
        Language language = Language.getInstance();
        List<String> mismatches = new ArrayList<>();
        for (String key : List.of("block.minecraft.stone", "item.minecraft.diamond", "entity.minecraft.zombie",
                "block.minecraft.torch", "item.minecraft.golden_apple")) {
            String expected = language.getOrDefault(key, null);
            assertTrue(expected != null, key + " is not in the game's language table");
            String extracted = data.has(key) ? data.get(key).getAsString() : null;
            if (!expected.equals(extracted)) {
                mismatches.add(key + " language=" + expected + " extracted=" + extracted);
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.toString());
    }

    /** Every downloaded locale becomes a document of its own, with the same shape as en_us. */
    @Test
    void everyLocaleGetsItsOwnDocument() {
        if (!TestSupport.extraction().languagesAvailable()) {
            return;
        }
        Path directory = TestSupport.extraction().output().resolve("translations/locales");
        List<Path> documents = TestSupport.jsonFiles(directory);
        assertTrue(documents.size() > 100, "expected most locales, got " + documents.size());

        for (Path document : documents) {
            JsonObject json = TestSupport.object(document);
            String locale = json.get("locale").getAsString();
            assertFalse(locale.isBlank(), document + " has no locale");
            assertEquals(locale, document.getFileName().toString().replace(".json", ""));
            JsonObject data = json.getAsJsonObject("data");
            assertTrue(data.size() > 100, locale + " only has " + data.size() + " keys");
            assertTrue(json.getAsJsonObject("_meta").has("entry_count"));
        }

        assertTrue(Files.isRegularFile(directory.resolve("en_us.json")), "en_us.json must be there too");
    }

    /** The manifest must describe every locale document and its coverage. */
    @Test
    void manifestDescribesEveryLocale() {
        JsonObject manifest = TestSupport.document("translations/locales.json");
        JsonObject locales = manifest.getAsJsonObject("locales");
        assertEquals("en_us", manifest.get("fallback_locale").getAsString());
        assertTrue(locales.has("en_us"));

        int registryKeys = manifest.get("registry_keys").getAsInt();
        JsonObject english = locales.getAsJsonObject("en_us");
        assertEquals(registryKeys, english.get("registry_keys").getAsInt());
        assertTrue(english.get("keys").getAsInt() > 5_000, "en_us should carry the full language table");

        if (!TestSupport.extraction().languagesAvailable()) {
            return;
        }
        long documents = TestSupport.jsonFiles(
                        TestSupport.extraction().output().resolve("translations/locales"))
                .size();
        assertEquals(documents, locales.size(),
                "the manifest and the locale documents disagree on the number of locales");

        // English is the reference locale, so it must be the best covered one.
        int englishTranslated = english.get("registry_keys_translated").getAsInt();
        for (String locale : locales.keySet()) {
            int translated = locales.getAsJsonObject(locale).get("registry_keys_translated").getAsInt();
            assertTrue(translated <= englishTranslated,
                    locale + " translates more registry keys than en_us, which cannot be right");
            assertTrue(locales.getAsJsonObject(locale).has("source"),
                    locale + " has no recorded source");
        }
    }
}
