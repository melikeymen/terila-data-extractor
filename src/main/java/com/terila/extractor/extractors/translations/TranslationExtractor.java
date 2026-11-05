package com.terila.extractor.extractors.translations;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.translations.model.TranslationData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes the language data the engine needs to display names in any locale.
 *
 * <ul>
 *   <li>{@code translations/en_us.json} - the full English key/value map (the historical location,
 *       kept so existing consumers do not have to move)</li>
 *   <li>{@code translations/locales/<locale>.json} - the same shape for every other locale the game
 *       ships, one document per language</li>
 *   <li>{@code translations/keys.json} - every key used by a block, item or entity registry entry,
 *       with the ones English is missing, which is what the engine resolves at runtime</li>
 *   <li>{@code translations/locales.json} - manifest: per locale key count, translation source and
 *       how much of the registry key set that locale covers</li>
 * </ul>
 *
 * <p>Two inputs are read, in this order:
 *
 * <ol>
 *   <li>{@code .mc-cache/lang} (or {@code TERILA_LANGUAGES} / {@code --lang-dir}), filled by
 *       {@code ./gradlew downloadLanguages} with every {@code minecraft/lang/<locale>.json} from
 *       Mojang's asset index. This is the only source for the ~140 locales that are not English.</li>
 *   <li>the client jar, which carries {@code en_us.json} and is therefore enough for a run that never
 *       downloaded the asset index.</li>
 * </ol>
 *
 * <p>A locale found in the jar is only used when the directory does not provide it, so a downloaded
 * set always wins.
 */
public final class TranslationExtractor extends AbstractExtractor {

    private static final String LANG_PREFIX = "assets/minecraft/lang/";

    /** Locale of the language file the engine falls back to, and of the compatibility document. */
    private static final String FALLBACK_LOCALE = "en_us";

    /** Subdirectory that holds one document per locale. */
    private static final String LOCALES_DIRECTORY = "translations/locales/";

    /**
     * Not a locale: this file lists removed/renamed keys and its values are nested structures, not
     * plain strings.
     */
    private static final String DEPRECATED_FILE = "deprecated.json";

    public TranslationExtractor() {
        super("translations", DataType.TRANSLATIONS, "translations/en_us.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Path clientJar = context.clientJar().orElse(null);
        Path languagesDir = context.languagesDir().filter(Files::isDirectory).orElse(null);
        if (languagesDir == null && (clientJar == null || !Files.isRegularFile(clientJar))) {
            context.errors().warning("LANGUAGE_FILES_UNAVAILABLE", name(), null,
                    "No language files found; run ./gradlew downloadLanguages (every locale) or pass "
                            + "--client-jar (en_us only)");
            return 0;
        }

        Map<String, LocaleSource> locales = new LinkedHashMap<>();
        if (languagesDir != null) {
            readDirectory(context, languagesDir, locales);
        }
        if (clientJar != null && Files.isRegularFile(clientJar)) {
            readClientJar(context, clientJar, locales);
        }

        LocaleSource english = locales.get(FALLBACK_LOCALE);
        if (english == null) {
            context.errors().error("LANG_FILE_MISSING", name(), FALLBACK_LOCALE, null,
                    "en_us.json was not found in " + (languagesDir == null ? clientJar : languagesDir));
            return 0;
        }

        write(context, english.data().toJson(context, name()));
        for (Map.Entry<String, LocaleSource> locale : locales.entrySet()) {
            context.writer().write(LOCALES_DIRECTORY + locale.getKey() + ".json",
                    localeDocument(context, locale.getKey(), locale.getValue()));
        }

        // Registry keys the engine must be able to resolve, and the ones no locale can resolve.
        JsonObject keys = new JsonObject();
        addKeys(keys, BuiltInRegistries.BLOCK, Block::getDescriptionId);
        addKeys(keys, BuiltInRegistries.ITEM, Item::getDescriptionId);
        JsonObject keysRoot = new JsonObject();
        keysRoot.add("_meta", context.meta("translation_keys", keys.size()));
        keysRoot.add("missing", missingFrom(english.data(), keys));
        keysRoot.add("data", keys);
        context.writer().write("translations/keys.json", keysRoot);

        writeManifest(context, locales, keys);
        return english.data().entries().size();
    }

    /** Reads every {@code <locale>.json} of the downloaded language directory. */
    private void readDirectory(ExtractorContext context, Path directory,
                               Map<String, LocaleSource> locales) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException failure) {
            context.errors().error("LANG_DIRECTORY_UNREADABLE", name(), directory.toString(), null,
                    String.valueOf(failure));
            return;
        }

        for (Path file : files) {
            String locale = file.getFileName().toString();
            locale = locale.substring(0, locale.length() - ".json".length());
            if (locale.isEmpty() || locale.equals(DEPRECATED_FILE.replace(".json", ""))) {
                continue;
            }
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file),
                    StandardCharsets.UTF_8)) {
                TranslationData data = parse(file.toString(), JsonParser.parseReader(reader));
                if (data != null) {
                    locales.put(locale, new LocaleSource(ASSET_INDEX_SOURCE, data));
                }
            } catch (IOException | RuntimeException failure) {
                context.errors().error("LANG_FILE_UNREADABLE", name(), file.toString(), null,
                        String.valueOf(failure));
            }
        }
        context.log().info("read %d locale(s) from %s", locales.size(), directory);
    }

    /** Reads the language files inside the client jar; {@code en_us.json} is the one that matters. */
    private void readClientJar(ExtractorContext context, Path clientJar,
                               Map<String, LocaleSource> locales) throws IOException {
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            for (ZipEntry entry : langEntries(zip)) {
                String locale = localeOf(entry.getName());
                if (locale == null || locales.containsKey(locale)) {
                    continue;
                }
                TranslationData data = read(zip, entry.getName());
                if (data != null) {
                    locales.put(locale, new LocaleSource(CLIENT_JAR_SOURCE, data));
                }
            }
        }
    }

    /** One locale document: {@code {_meta, locale, source, data}}. */
    private static JsonObject localeDocument(ExtractorContext context, String locale,
                                             LocaleSource source) {
        JsonObject root = new JsonObject();
        root.add("_meta", context.meta("locale:" + locale, source.data().entries().size()));
        root.addProperty("locale", locale);
        root.addProperty("source", source.origin());
        JsonObject data = new JsonObject();
        source.data().entries().forEach(data::addProperty);
        root.add("data", data);
        return root;
    }

    /** Manifest with per-locale key counts and coverage of the registry key set. */
    private static void writeManifest(ExtractorContext context, Map<String, LocaleSource> locales,
                                      JsonObject keys) throws IOException {
        JsonObject manifest = new JsonObject();
        for (Map.Entry<String, LocaleSource> entry : locales.entrySet()) {
            JsonObject locale = new JsonObject();
            Map<String, String> entries = entry.getValue().data().entries();
            locale.addProperty("keys", entries.size());
            locale.addProperty("source", entry.getValue().origin());
            int translated = 0;
            for (JsonElement key : keys.asMap().values()) {
                if (entries.containsKey(key.getAsString())) {
                    translated++;
                }
            }
            locale.addProperty("registry_keys", keys.size());
            locale.addProperty("registry_keys_translated", translated);
            locale.addProperty("registry_keys_missing", keys.size() - translated);
            manifest.add(entry.getKey(), locale);
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta("locales", locales.size()));
        root.addProperty("fallback_locale", FALLBACK_LOCALE);
        root.addProperty("registry_keys", keys.size());
        root.add("locales", manifest);
        context.writer().write("translations/locales.json", root);
    }

    private static List<ZipEntry> langEntries(ZipFile zip) {
        return zip.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().startsWith(LANG_PREFIX))
                .filter(entry -> entry.getName().endsWith(".json"))
                .filter(entry -> !entry.getName().endsWith(DEPRECATED_FILE))
                .sorted(java.util.Comparator.comparing(ZipEntry::getName))
                .map(entry -> (ZipEntry) entry)
                .toList();
    }

    private static String localeOf(String entryName) {
        String file = entryName.substring(LANG_PREFIX.length());
        return file.endsWith(".json") ? file.substring(0, file.length() - ".json".length()) : null;
    }

    private static TranslationData read(ZipFile zip, String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream stream = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return parse(entryName, JsonParser.parseReader(reader));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read " + entryName, failure);
        }
    }

    /** Turns one language file into sorted key/value pairs, skipping non-string values. */
    private static TranslationData parse(String source, JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return null;
        }
        TreeMap<String, String> entries = new TreeMap<>();
        int skipped = 0;
        for (var field : json.getAsJsonObject().entrySet()) {
            JsonElement value = field.getValue();
            // Keys are allowed to hold non-string values (the deprecated list does); those are not
            // display names, so they are counted and skipped rather than failing the run.
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                entries.put(field.getKey(), value.getAsString());
            } else {
                skipped++;
            }
        }
        return new TranslationData(source, entries, skipped);
    }

    private static <T> void addKeys(JsonObject target, net.minecraft.core.Registry<T> registry,
                                    java.util.function.Function<T, String> keyFn) {
        for (T value : registry) {
            Identifier id = registry.getKey(value);
            if (id != null) {
                target.addProperty(id.toString(), keyFn.apply(value));
            }
        }
    }

    private static JsonObject missingFrom(TranslationData english, JsonObject keys) {
        JsonObject missing = new JsonObject();
        for (var field : keys.entrySet()) {
            if (!english.entries().containsKey(field.getValue().getAsString())) {
                missing.addProperty(field.getKey(), field.getValue().getAsString());
            }
        }
        return missing;
    }

    /** Where a locale's strings came from. */
    private static final String ASSET_INDEX_SOURCE = "minecraft:asset-index";

    /** Where a locale's strings came from when only the client jar is available. */
    private static final String CLIENT_JAR_SOURCE = "client-jar";

    /** One locale plus where it was read from. */
    private record LocaleSource(String origin, TranslationData data) {
    }
}
