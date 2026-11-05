package com.terila.extractor.extractors.tags;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import com.terila.extractor.bootstrap.VanillaDataLoader;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorLogger;
import com.terila.extractor.core.FunctionTagSource;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the merged function tag view against the game's loader. */
class EffectiveFunctionTagExtractorTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void bootStrap() {
        TestSupport.bootStrap();
    }

    @Test
    void writesSortedEffectiveTagsAndFunctions() throws Exception {
        Map<String, List<Identifier>> tags = new TreeMap<>();
        tags.put("demo:b", List.of(Identifier.parse("demo:b")));
        tags.put("demo:a", List.of(Identifier.parse("demo:b"), Identifier.parse("demo:a")));
        FunctionTagSource source = source(tags, 2);

        Path output = temp.resolve("fake-output");
        ExtractorContext context = context(output, source);
        assertEquals(2, new EffectiveFunctionTagExtractor().extract(context));

        JsonObject document = TestSupport.object(output.resolve("tags/effective_functions.json"));
        assertEquals(2, document.get("function_count").getAsInt());
        assertEquals(2, document.get("tag_count").getAsInt());
        assertEquals(3, document.get("function_reference_count").getAsInt());
        assertTrue(document.get("vanilla_provides").getAsBoolean());
        assertFalse(document.has("note"));

        JsonObject first = document.getAsJsonArray("data").get(0).getAsJsonObject();
        assertEquals("demo:a", first.get("id").getAsString());
        assertEquals(List.of("demo:a", "demo:b"), first.getAsJsonArray("functions").asList()
                .stream().map(element -> element.getAsString()).toList());
    }

    @Test
    void reportsMissingFunctionLibraryWithoutWritingADocument() throws Exception {
        Path output = temp.resolve("missing-output");
        ExtractorContext context = ExtractorContext.builder()
                .config(ExtractorConfig.builder(output).prettyPrint(true).build())
                .build();

        assertEquals(0, new EffectiveFunctionTagExtractor().extract(context));
        assertFalse(Files.exists(output.resolve("tags/effective_functions.json")));
        assertTrue(context.errors().warnings().stream()
                .anyMatch(issue -> issue.type().equals("FUNCTION_TAGS_UNAVAILABLE")));
    }

    /** The real loader has to merge a tag and let replace:true override the lower pack. */
    @Test
    void usesTheGameLoaderForMergeAndReplaceAcrossPacks() throws Exception {
        Path first = pack("first", "a");
        Path second = pack("second", "b");

        Path output = temp.resolve("merge-output");
        try (VanillaDataLoader data = VanillaDataLoader.loadPacks(List.of(first, second),
                ExtractorLogger.scoped("test"))) {
            ExtractorContext context = ExtractorContext.builder()
                    .config(ExtractorConfig.builder(output).prettyPrint(true).build())
                    .functionTags(data.functionTags())
                    .pack(data.pack())
                    .build();
            assertEquals(2, new EffectiveFunctionTagExtractor().extract(context));
            assertEquals(2, new FunctionTagExtractor().extract(context));
        }

        JsonObject document = TestSupport.object(output.resolve("tags/effective_functions.json"));
        JsonObject load = tag(document, "demo:load");
        assertEquals(List.of("demo:a", "demo:b"), functions(load));

        JsonObject replaced = tag(document, "demo:replace");
        assertEquals(List.of("demo:b"), functions(replaced));

        // The raw document only sees the winning pack file. The consistency report has to name the
        // lower-pack member that only the game's merged view carries.
        JsonObject raw = TestSupport.object(output.resolve("tags/functions.json"));
        assertTrue(raw.get("effective_view_available").getAsBoolean());
        assertEquals(2, raw.get("effective_tag_count").getAsInt());
        assertEquals(3, raw.get("effective_function_reference_count").getAsInt());
        assertEquals(1, raw.get("mismatched_tag_count").getAsInt());
        assertFalse(raw.get("matches_effective_view").getAsBoolean());
        assertEquals(1, raw.getAsJsonArray("effective_differences").size());

        JsonObject difference = raw.getAsJsonArray("effective_differences").get(0).getAsJsonObject();
        assertEquals("demo:load", difference.get("id").getAsString());
        assertEquals(List.of(), strings(difference.getAsJsonArray("only_declared_functions")));
        assertEquals(List.of("demo:a"),
                strings(difference.getAsJsonArray("only_effective_functions")));

        JsonObject rawLoad = tag(raw, "demo:load");
        assertEquals(List.of("demo:b"), strings(rawLoad.getAsJsonArray("resolved_functions")));
        assertEquals(List.of("demo:a", "demo:b"),
                strings(rawLoad.getAsJsonArray("effective_functions")));
        assertFalse(rawLoad.get("matches_effective").getAsBoolean());

        JsonObject rawReplaced = tag(raw, "demo:replace");
        assertEquals(List.of("demo:b"), strings(rawReplaced.getAsJsonArray("resolved_functions")));
        assertEquals(List.of("demo:b"),
                strings(rawReplaced.getAsJsonArray("effective_functions")));
        assertTrue(rawReplaced.get("matches_effective").getAsBoolean());
    }

    private static ExtractorContext context(Path output, FunctionTagSource source) {
        return ExtractorContext.builder()
                .config(ExtractorConfig.builder(output).prettyPrint(true).build())
                .functionTags(source)
                .build();
    }

    private static FunctionTagSource source(Map<String, List<Identifier>> tags, int functionCount) {
        return new FunctionTagSource() {
            @Override
            public Iterable<Identifier> tagIds() {
                return tags.keySet().stream().map(Identifier::parse).toList();
            }

            @Override
            public List<Identifier> functions(Identifier tag) {
                return tags.getOrDefault(tag.toString(), List.of());
            }

            @Override
            public int functionCount() {
                return functionCount;
            }
        };
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.getAsString()));
        return values;
    }

    private static JsonObject tag(JsonObject document, String id) {
        for (var element : document.getAsJsonArray("data")) {
            JsonObject tag = element.getAsJsonObject();
            if (tag.get("id").getAsString().equals(id)) {
                return tag;
            }
        }
        throw new AssertionError("missing tag " + id + " in " + document);
    }

    private static List<String> functions(JsonObject tag) {
        List<String> functions = new ArrayList<>();
        tag.getAsJsonArray("functions").forEach(element -> functions.add(element.getAsString()));
        return functions;
    }

    /**
     * Two packs: both add to {@code demo:load}, while {@code demo:replace} is replaced by the second.
     *
     * <p>The function name is appended to the pack id so each pack file is easy to identify in a
     * failure message.
     */
    private Path pack(String name, String function) throws IOException {
        Path pack = temp.resolve(name);
        Files.createDirectories(pack.resolve("data/demo/function"));
        Files.createDirectories(pack.resolve("data/demo/tags/function"));
        Files.writeString(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":121,"description":"Terila tag merge test"}}
                """, StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("data/demo/function/" + function + ".mcfunction"),
                "say " + function + "\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("data/demo/tags/function/load.json"),
                "{\"values\":[\"demo:" + function + "\"]}\n", StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("data/demo/tags/function/replace.json"),
                "{\"replace\":true,\"values\":[\"demo:" + function + "\"]}\n",
                StandardCharsets.UTF_8);
        return pack;
    }
}

