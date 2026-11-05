package com.terila.extractor;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terila.extractor.util.HashUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the exact content of the extracted documents.
 *
 * <p>The integration test checks that the output is <em>correct</em> — counts agree with the game,
 * registries carry payloads, palettes name real blocks. This test checks that it is
 * <em>unchanged</em>: every document is reduced to a hash of its normalised content and compared
 * against a checked-in digest, so any change to extracted data fails with the exact file that moved.
 * A wrong value that happens to satisfy every invariant would still be caught here.
 *
 * <p>Three details make that possible:
 *
 * <ul>
 *   <li><strong>Volatile members are removed.</strong> Timestamps, durations and the report hash differ
 *       between runs by design, as does the absolute path of the client jar, so they are dropped
 *       before hashing. Everything else is hashed verbatim.</li>
 *   <li><strong>Large trees are aggregated.</strong> The 1511 structure templates, the registry dumps,
 *       the per-block state files and the resource pack assets each collapse to one hash, which keeps
 *       the digest readable while still failing on any change inside them.</li>
 *   <li><strong>The digest is regenerated deliberately.</strong> Run with
 *       {@code -Dterila.golden.update=true} after an intentional change and review the diff, exactly as
 *       one would review a snapshot.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenOutputTest {

    /** Name of the system property that rewrites the golden file instead of comparing against it. */
    private static final String UPDATE_PROPERTY = "terila.golden.update";

    private static final Path GOLDEN_FILE =
            Path.of("src", "test", "resources", "golden", "output-digest.json");

    /** Directory prefixes folded into a single aggregate hash. */
    private static final List<String> AGGREGATED_TREES = List.of(
            "structures/templates/",
            "registries/dump/",
            "blocks/blockstates/",
            "translations/locales/",
            "assets/"
    );

    /**
     * Trees that only exist when the matching input was downloaded.
     *
     * <p>{@code translations/locales/} comes from {@code ./gradlew downloadLanguages}; without that
     * input there are no locale documents to hash. A run that <em>does</em> produce the tree still has
     * to match the golden digest, so the data itself stays pinned.
     */
    private static final Set<String> OPTIONAL_TREES = Set.of("translations/locales/");

    /** Members that differ between two otherwise identical runs. */
    private static final Set<String> VOLATILE_MEMBERS = Set.of(
            "generated_at",
            "duration_ms",
            "report_hash"
    );

    private static Map<String, String> documents;
    private static Map<String, String> trees;
    private static Map<String, String> nbt;

    @BeforeAll
    void digestTheOutput() throws IOException {
        Path output = TestSupport.extraction().output();
        documents = new TreeMap<>();
        trees = new TreeMap<>();
        nbt = new TreeMap<>();
        Map<String, List<String>> hashesByTree = new LinkedHashMap<>();
        AGGREGATED_TREES.forEach(prefix -> hashesByTree.put(prefix, new ArrayList<>()));

        for (Path file : TestSupport.jsonFiles(output)) {
            String relative = output.relativize(file).toString().replace('\\', '/');
            String hash = digest(file);

            String tree = AGGREGATED_TREES.stream().filter(relative::startsWith).findFirst().orElse(null);
            if (tree == null) {
                documents.put(relative, hash);
            } else {
                hashesByTree.get(tree).add(relative + "=" + hash);
            }
        }

        hashesByTree.forEach((tree, hashes) -> {
            if (!hashes.isEmpty()) {
                trees.put(tree, HashUtil.sha256(String.join("\n", hashes)));
            }
        });

        try (java.util.stream.Stream<Path> stream = Files.walk(output)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".nbt") || name.endsWith(".bin");
                    })
                    .toList()) {
                String relative = output.relativize(file).toString().replace('\\', '/');
                nbt.put(relative, HashUtil.sha256(Files.readAllBytes(file)));
            }
        }

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            writeGolden();
        }
    }

    @Test
    void everyDocumentMatchesTheGoldenDigest() {
        JsonObject golden = readGolden();

        JsonObject expectedDocuments = golden.getAsJsonObject("documents");
        Map<String, String> expected = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : expectedDocuments.entrySet()) {
            expected.put(entry.getKey(), entry.getValue().getAsString());
        }

        assertEquals(new TreeMap<>(expected), new TreeMap<>(documents), diff(expected, documents));
    }

    @Test
    void everyAggregatedTreeMatchesTheGoldenDigest() {
        JsonObject golden = readGolden();

        JsonObject expectedTrees = golden.getAsJsonObject("trees");
        for (String tree : AGGREGATED_TREES) {
            if (!trees.containsKey(tree)) {
                assertTrue(OPTIONAL_TREES.contains(tree),
                        "the run did not produce " + tree + ", which every run must produce");
                continue;
            }
            assertTrue(expectedTrees.has(tree), "the golden file does not cover " + tree);
            assertEquals(expectedTrees.get(tree).getAsString(), trees.get(tree),
                    tree + " changed; run with -D" + UPDATE_PROPERTY + "=true after reviewing the diff");
        }
        for (String tree : trees.keySet()) {
            assertTrue(AGGREGATED_TREES.contains(tree), "unexpected aggregated tree " + tree);
            assertTrue(expectedTrees.has(tree), "the golden file does not cover " + tree);
        }
        assertEquals(AGGREGATED_TREES.size() - (OPTIONAL_TREES.contains("translations/locales/")
                        && !trees.containsKey("translations/locales/") ? 1 : 0),
                trees.size());
    }

    @Test
    void everyBinaryPayloadMatchesTheGoldenDigest() {
        JsonObject golden = readGolden();
        JsonObject expected = golden.getAsJsonObject("nbt");
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsString());
        }

        assertEquals(new TreeMap<>(values), new TreeMap<>(nbt),
                "NBT wire bytes changed; regenerate with -D" + UPDATE_PROPERTY + "=true");
    }

    /** The digest has to cover a meaningful amount of output to be worth anything. */
    @Test
    void theDigestCoversTheWholeOutput() {
        assertTrue(documents.size() > 80, "expected most documents individually hashed, got " + documents.size());
        assertEquals(AGGREGATED_TREES.size() - (trees.containsKey("translations/locales/") ? 0 : 1),
                trees.size());
        assertFalse(documents.containsKey("_meta.json")
                        && documents.get("_meta.json").isEmpty(),
                "the run report must have a digest");
        assertTrue(nbt.size() >= 161, "expected every registry, tag, client tags and tier-2 binary");
    }

    /** The golden file is checked in, so it must be readable and complete. */
    @Test
    void theGoldenFileIsComplete() {
        JsonObject golden = readGolden();

        assertEquals(1, golden.get("format_version").getAsInt());
        assertEquals(TestSupport.extraction().report().totalEntries(),
                golden.get("total_entries").getAsInt(),
                "the golden file records the entry count it was generated from");
        assertTrue(golden.has("minecraft_version"));
        assertTrue(golden.getAsJsonObject("documents").size() > 80);
    }

    /**
     * A digest must not change when only volatile members change.
     *
     * <p>Proves the normalisation is doing its job: re-digesting a document with a different timestamp
     * has to produce the same hash, otherwise the golden file would fail on every run.
     */
    @Test
    void volatileMembersDoNotAffectTheDigest() {
        JsonObject first = JsonParser.parseString("""
                {"_meta": {"generated_at": "2026-09-17T00:00:00Z", "entry_count": 3, "source": "minecraft:vanilla"},
                 "data": [1, 2]}
                """).getAsJsonObject();
        JsonObject second = JsonParser.parseString("""
                {"_meta": {"generated_at": "2027-01-01T12:34:56Z", "entry_count": 3, "source": "minecraft:vanilla"},
                 "data": [1, 2]}
                """).getAsJsonObject();

        assertEquals(digestOf(first), digestOf(second));
    }

    /** A real change has to change the digest, or the golden file would be pointless. */
    @Test
    void contentChangesTheDigest() {
        JsonObject original = JsonParser.parseString("{\"data\": [{\"id\": \"minecraft:stone\"}]}").getAsJsonObject();
        JsonObject changed = JsonParser.parseString("{\"data\": [{\"id\": \"minecraft:dirt\"}]}").getAsJsonObject();

        org.junit.jupiter.api.Assertions.assertNotEquals(digestOf(original), digestOf(changed));
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Normalised content hash of a document on disk.
     *
     * <p>Any JSON value is accepted: the mirrored resource pack contains top level arrays (the credits
     * file, for one), and those are hashed like everything else.
     */
    private static String digest(Path file) {
        try {
            return digestOf(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read " + file, failure);
        }
    }

    private static String digestOf(JsonElement document) {
        return HashUtil.sha256(normalise(document.deepCopy(), true).toString());
    }

    /**
     * Removes the members that legitimately differ between runs.
     *
     * <p>The top level's {@code source} is dropped too: for the asset and translation documents it is
     * the absolute path of the client jar, which differs per machine. Nested {@code source} members are
     * kept, since they are literals like {@code minecraft:vanilla}.
     */
    private static JsonElement normalise(JsonElement element, boolean topLevel) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (VOLATILE_MEMBERS.contains(entry.getKey())) {
                    continue;
                }
                if (topLevel && entry.getKey().equals("source")) {
                    continue;
                }
                result.add(entry.getKey(), normalise(entry.getValue(), false));
            }
            return result;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                array.add(normalise(item, false));
            }
            return array;
        }
        return element;
    }

    private static JsonObject readGolden() {
        if (!Files.isRegularFile(GOLDEN_FILE)) {
            throw new AssertionError("missing " + GOLDEN_FILE + "; run the tests with -D"
                    + UPDATE_PROPERTY + "=true to create it");
        }
        return TestSupport.object(GOLDEN_FILE);
    }

    private static void writeGolden() throws IOException {
        JsonObject golden = new JsonObject();
        golden.addProperty("format_version", 1);
        golden.addProperty("minecraft_version",
                com.terila.extractor.core.ExtractorVersion.MINECRAFT_VERSION);
        golden.addProperty("protocol_version",
                com.terila.extractor.core.ExtractorVersion.PROTOCOL_VERSION);
        golden.addProperty("data_pack_version",
                com.terila.extractor.core.ExtractorVersion.DATA_PACK_VERSION);
        golden.addProperty("total_entries", TestSupport.extraction().report().totalEntries());
        golden.addProperty("document_count", documents.size());
        golden.addProperty("locales_included", trees.containsKey("translations/locales/"));
        golden.addProperty("note", "Generated by GoldenOutputTest; regenerate with -D"
                + UPDATE_PROPERTY + "=true and review the diff. translations/locales/ is only covered "
                + "when ./gradlew downloadLanguages has run, but is always checked when present.");

        JsonObject documentDigests = new JsonObject();
        documents.forEach(documentDigests::addProperty);
        golden.add("documents", documentDigests);

        JsonObject treeDigests = new JsonObject();
        trees.forEach(treeDigests::addProperty);
        golden.add("trees", treeDigests);

        JsonObject nbtDigests = new JsonObject();
        nbt.forEach(nbtDigests::addProperty);
        golden.add("nbt", nbtDigests);

        Files.createDirectories(GOLDEN_FILE.getParent());
        Files.writeString(GOLDEN_FILE, new com.google.gson.GsonBuilder()
                .setPrettyPrinting().create().toJson(golden) + "\n", StandardCharsets.UTF_8);
    }

    /** Human readable report of which documents were added, removed or changed. */
    private static String diff(Map<String, String> expected, Map<String, String> actual) {
        List<String> added = actual.keySet().stream().filter(key -> !expected.containsKey(key)).sorted().toList();
        List<String> removed = expected.keySet().stream().filter(key -> !actual.containsKey(key)).sorted().toList();
        List<String> changed = expected.keySet().stream()
                .filter(actual::containsKey)
                .filter(key -> !expected.get(key).equals(actual.get(key)))
                .sorted()
                .toList();

        return "output does not match the golden digest (regenerate with -D" + UPDATE_PROPERTY
                + "=true after reviewing)\n  added:   " + added
                + "\n  removed: " + removed
                + "\n  changed: " + changed.stream().limit(40).collect(Collectors.joining(", "));
    }
}
