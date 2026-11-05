package com.terila.extractor.extractors.functions;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import com.terila.extractor.bootstrap.VanillaDataLoader;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorLogger;
import com.terila.extractor.extractors.tags.FunctionTagExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests function extraction against a real datapack overlay.
 *
 * <p>Vanilla has no functions, so the full extraction fixture cannot exercise the text path. This
 * test builds a minimal pack, loads it through the same {@link VanillaDataLoader} a real run uses,
 * and checks both halves of function support: the {@code .mcfunction} sources and the function tags
 * that reference them.
 */
class FunctionExtractionTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void bootStrap() {
        TestSupport.bootStrap();
    }

    @Test
    void extractsFunctionsAndFunctionTagsThroughLoadedPacks() throws Exception {
        Path pack = functionPack();
        Path output = temp.resolve("output");

        try (VanillaDataLoader data = VanillaDataLoader.load(pack, ExtractorLogger.scoped("test"))) {
            ExtractorContext context = context(output, data);

            assertEquals(2, new FunctionExtractor().extract(context));
            assertEquals(2, new FunctionTagExtractor().extract(context));
        }

        JsonObject document = TestSupport.object(output.resolve("functions/minecraft.json"));
        assertEquals(2, document.get("function_count").getAsInt());
        assertEquals(5, document.get("total_line_count").getAsInt());
        assertEquals(3, document.get("total_command_count").getAsInt());
        assertEquals(1, document.get("call_count").getAsInt());
        assertEquals(0, document.get("dynamic_call_count").getAsInt());
        assertEquals(0, document.get("tag_call_count").getAsInt());
        assertEquals(0, document.get("tag_expansion_mismatch_count").getAsInt());
        assertTrue(document.get("effective_tag_expansion_available").getAsBoolean());
        assertTrue(document.getAsJsonArray("tag_expansion_differences").isEmpty());
        assertEquals(0, document.get("missing_reference_count").getAsInt());
        assertEquals(0, document.get("broken_function_count").getAsInt());
        assertEquals(0, document.get("call_cycle_count").getAsInt());
        assertEquals(0, document.get("self_call_count").getAsInt());
        assertTrue(document.getAsJsonArray("cycles").isEmpty());
        assertTrue(document.get("vanilla_provides").getAsBoolean());
        assertFalse(document.has("note"));

        List<String> ids = new ArrayList<>();
        for (var element : document.getAsJsonArray("data")) {
            ids.add(element.getAsJsonObject().get("id").getAsString());
        }
        assertEquals(List.of("demo:sub/nested", "demo:tick"), ids);

        JsonObject tick = entry(document, "demo:tick");
        assertEquals("data/demo/function/tick.mcfunction", tick.get("resource").getAsString());
        assertEquals(4, tick.get("line_count").getAsInt());
        assertEquals(2, tick.get("command_count").getAsInt());
        assertEquals(1, tick.get("comment_count").getAsInt());
        assertEquals(1, tick.get("blank_line_count").getAsInt());
        assertEquals(1, tick.get("macro_count").getAsInt());
        assertEquals(0, tick.get("call_count").getAsInt());
        assertTrue(tick.get("sha256").getAsString().startsWith("sha256:"));

        // The source was written with CRLF on purpose; the document normalises it without touching
        // the commands themselves.
        String content = tick.get("content").getAsString();
        assertFalse(content.contains("\r"), "line endings were not normalised");
        assertEquals("# the server tick loop\nsay hello\n\n$say $(who)\n", content);

        JsonObject nested = entry(document, "demo:sub/nested");
        assertEquals(1, nested.get("call_count").getAsInt());
        assertTrue(nested.getAsJsonArray("missing_references").isEmpty());
        JsonObject call = nested.getAsJsonArray("calls").get(0).getAsJsonObject();
        assertEquals("demo:tick", call.get("target").getAsString());
        assertEquals("function", call.get("type").getAsString());
        assertEquals(1, call.get("line").getAsInt());
        assertFalse(call.get("dynamic").getAsBoolean());
        assertTrue(call.get("exists").getAsBoolean());

        JsonObject tags = TestSupport.object(output.resolve("tags/functions.json"));
        assertEquals(2, tags.get("tag_count").getAsInt());
        assertEquals(2, tags.get("function_count").getAsInt());
        assertEquals(2, tags.get("reference_count").getAsInt());
        assertEquals(1, tags.get("function_reference_count").getAsInt());
        assertEquals(1, tags.get("tag_reference_count").getAsInt());
        assertEquals(2, tags.get("resolved_function_reference_count").getAsInt());
        assertEquals(0, tags.get("missing_reference_count").getAsInt());
        assertEquals(0, tags.get("broken_tag_count").getAsInt());
        assertTrue(tags.get("all_references_resolve").getAsBoolean());
        assertTrue(tags.get("effective_view_available").getAsBoolean());
        assertEquals(2, tags.get("effective_tag_count").getAsInt());
        assertEquals(0, tags.get("mismatched_tag_count").getAsInt());
        assertTrue(tags.get("matches_effective_view").getAsBoolean());
        assertTrue(tags.getAsJsonArray("effective_differences").isEmpty());

        JsonObject load = entry(tags, "demo:load");
        assertEquals(1, load.getAsJsonArray("values").size());
        assertEquals("demo:tick", load.getAsJsonArray("values").get(0).getAsString());
        assertFalse(load.get("replace").getAsBoolean());
        assertEquals("demo:tick", load.getAsJsonArray("direct_functions").get(0).getAsString());
        assertEquals("demo:tick", load.getAsJsonArray("resolved_functions").get(0).getAsString());
        assertTrue(load.getAsJsonArray("missing_references").isEmpty());
        assertFalse(load.get("cyclic").getAsBoolean());
        assertEquals("demo:tick", load.getAsJsonArray("effective_functions").get(0).getAsString());
        assertTrue(load.get("matches_effective").getAsBoolean());

        JsonObject reference = load.getAsJsonArray("references").get(0).getAsJsonObject();
        assertEquals("demo:tick", reference.get("id").getAsString());
        assertEquals("function", reference.get("type").getAsString());
        assertTrue(reference.get("required").getAsBoolean());
        assertTrue(reference.get("exists").getAsBoolean());

        JsonObject all = entry(tags, "demo:all");
        assertEquals("demo:load", all.getAsJsonArray("nested_tags").get(0).getAsString());
        assertEquals("demo:tick", all.getAsJsonArray("resolved_functions").get(0).getAsString());
        assertEquals(1, all.get("resolved_function_count").getAsInt());
        assertFalse(all.get("cyclic").getAsBoolean());
    }

    /** With no functions the document has to say why it is empty, not just be an empty array. */
    /** Missing calls must be visible in the document and in the run warnings. */
    @Test
    void reportsMissingFunctionCallsAndSkipsDynamicTargets() throws Exception {
        Path pack = temp.resolve("call-pack");
        Path functions = pack.resolve("data/demo/function");
        Path tags = pack.resolve("data/demo/tags/function");
        Files.createDirectories(functions);
        Files.createDirectories(tags);
        Files.writeString(functions.resolve("caller.mcfunction"), """
                function demo:missing
                execute as @a run function #demo:tag
                function $(dynamic)
                """, StandardCharsets.UTF_8);
        Files.writeString(tags.resolve("tag.json"), "{\"values\":[]}\n", StandardCharsets.UTF_8);

        Path output = temp.resolve("call-output");
        ExtractorConfig config = ExtractorConfig.builder(output).prettyPrint(true).build();
        ExtractorContext context = ExtractorContext.builder()
                .config(config)
                .datapackRoot(pack)
                .build();

        assertEquals(1, new FunctionExtractor().extract(context));

        JsonObject document = TestSupport.object(output.resolve("functions/minecraft.json"));
        assertEquals(1, document.get("function_count").getAsInt());
        assertEquals(3, document.get("call_count").getAsInt());
        assertEquals(1, document.get("dynamic_call_count").getAsInt());
        assertEquals(1, document.get("tag_call_count").getAsInt());
        assertEquals(0, document.get("tag_expansion_mismatch_count").getAsInt());
        assertFalse(document.get("effective_tag_expansion_available").getAsBoolean());
        assertTrue(document.getAsJsonArray("tag_expansion_differences").isEmpty());
        assertEquals(1, document.get("missing_reference_count").getAsInt());
        assertEquals(1, document.get("broken_function_count").getAsInt());

        JsonObject caller = entry(document, "demo:caller");
        assertEquals(List.of("demo:missing"),
                caller.getAsJsonArray("missing_references").asList().stream()
                        .map(JsonElement::getAsString).toList());

        JsonObject tagCall = caller.getAsJsonArray("calls").get(1).getAsJsonObject();
        assertEquals("demo:tag", tagCall.get("target").getAsString());
        assertEquals("tag", tagCall.get("type").getAsString());
        assertTrue(tagCall.get("exists").getAsBoolean());

        JsonObject dynamic = caller.getAsJsonArray("calls").get(2).getAsJsonObject();
        assertTrue(dynamic.get("dynamic").getAsBoolean());
        assertFalse(dynamic.get("exists").getAsBoolean());

        assertTrue(context.errors().warnings().stream()
                        .anyMatch(issue -> issue.type().equals("MISSING_FUNCTION_CALL")),
                "warnings: " + context.errors().warnings());
        assertTrue(context.errors().errors().isEmpty());
    }

    /** A direct loop, a loop through a tag and a self-call all have to be visible. */
    @Test
    void detectsDirectAndTagMediatedCallCycles() throws Exception {
        Path pack = temp.resolve("cycle-pack");
        Path functions = pack.resolve("data/demo/function");
        Path tags = pack.resolve("data/demo/tags/function");
        Files.createDirectories(functions);
        Files.createDirectories(tags);
        Files.writeString(functions.resolve("direct_a.mcfunction"),
                "function demo:direct_b\n", StandardCharsets.UTF_8);
        Files.writeString(functions.resolve("direct_b.mcfunction"),
                "function demo:direct_a\n", StandardCharsets.UTF_8);
        Files.writeString(functions.resolve("tagged.mcfunction"),
                "function #demo:loop\n", StandardCharsets.UTF_8);
        Files.writeString(functions.resolve("self.mcfunction"),
                "function demo:self\n", StandardCharsets.UTF_8);
        Files.writeString(tags.resolve("loop.json"), """
                {"values":["demo:tagged"]}
                """, StandardCharsets.UTF_8);

        Path output = temp.resolve("cycle-output");
        ExtractorConfig config = ExtractorConfig.builder(output).prettyPrint(true).build();
        ExtractorContext context = ExtractorContext.builder()
                .config(config)
                .datapackRoot(pack)
                .build();

        assertEquals(4, new FunctionExtractor().extract(context));

        JsonObject document = TestSupport.object(output.resolve("functions/minecraft.json"));
        assertEquals(4, document.get("function_count").getAsInt());
        assertEquals(4, document.get("call_count").getAsInt());
        assertEquals(1, document.get("tag_call_count").getAsInt());
        assertEquals(0, document.get("tag_expansion_mismatch_count").getAsInt());
        assertFalse(document.get("effective_tag_expansion_available").getAsBoolean());
        assertTrue(document.getAsJsonArray("tag_expansion_differences").isEmpty());
        assertEquals(0, document.get("missing_reference_count").getAsInt());
        assertEquals(3, document.get("call_cycle_count").getAsInt());
        assertEquals(2, document.get("self_call_count").getAsInt());
        assertEquals(4, document.get("cyclic_function_count").getAsInt());
        assertEquals(3, document.getAsJsonArray("cycles").size());

        JsonObject tagged = entry(document, "demo:tagged");
        assertTrue(tagged.getAsJsonArray("calls").get(0).getAsJsonObject()
                .get("exists").getAsBoolean());
        assertTrue(context.errors().warnings().stream()
                        .anyMatch(issue -> issue.type().equals("FUNCTION_CALL_CYCLE")),
                "warnings: " + context.errors().warnings());
        assertEquals(2, context.errors().warnings().stream()
                .filter(issue -> issue.type().equals("FUNCTION_SELF_CALL")).count());
        assertTrue(context.errors().errors().isEmpty());
    }

    /**
     * A tag member merged in from a lower pack has to take part in the call graph.
     *
     * <p>The raw {@code demo:load} file only contains the upper pack's {@code demo:b}, and the lower
     * {@code demo:a} is the only member that closes a loop. The replaced tag checks the other half:
     * its lower member must not enter the graph once {@code replace: true} wins.
     */
    @Test
    void expandsMergedTagMembersWhenFindingCallCycles() throws Exception {
        Path first = mergedTagCyclePack("merged-first", false);
        Path second = mergedTagCyclePack("merged-second", true);
        Path output = temp.resolve("merged-cycle-output");
        ExtractorContext context;

        try (VanillaDataLoader data = VanillaDataLoader.loadPacks(List.of(first, second),
                ExtractorLogger.scoped("test"))) {
            context = context(output, data);
            assertEquals(6, new FunctionExtractor().extract(context));
        }

        JsonObject document = TestSupport.object(output.resolve("functions/minecraft.json"));
        assertEquals(6, document.get("function_count").getAsInt());
        assertEquals(4, document.get("call_count").getAsInt());
        assertEquals(2, document.get("tag_call_count").getAsInt());
        // demo:load gains demo:a from the lower pack; demo:replace agrees with its raw file.
        assertEquals(1, document.get("tag_expansion_mismatch_count").getAsInt());
        assertTrue(document.get("effective_tag_expansion_available").getAsBoolean());
        assertEquals(1, document.getAsJsonArray("tag_expansion_differences").size());
        JsonObject difference = document.getAsJsonArray("tag_expansion_differences")
                .get(0).getAsJsonObject();
        assertEquals("demo:load", difference.get("id").getAsString());
        assertEquals(1, difference.get("call_count").getAsInt());
        assertTrue(difference.getAsJsonArray("only_raw_functions").isEmpty());
        assertEquals(List.of("demo:a"),
                difference.getAsJsonArray("only_effective_functions").asList().stream()
                        .map(JsonElement::getAsString).toList());
        assertEquals(1, document.get("call_cycle_count").getAsInt());
        assertEquals(0, document.get("self_call_count").getAsInt());
        assertEquals(2, document.get("cyclic_function_count").getAsInt());

        JsonObject cycle = document.getAsJsonArray("cycles").get(0).getAsJsonObject();
        assertEquals(List.of("demo:a", "demo:entry"),
                cycle.getAsJsonArray("functions").asList().stream()
                        .map(JsonElement::getAsString).toList());
        assertFalse(cycle.get("self_call").getAsBoolean());
        assertTrue(context.errors().warnings().stream()
                        .anyMatch(issue -> issue.type().equals("FUNCTION_CALL_CYCLE")),
                "warnings: " + context.errors().warnings());
        assertTrue(context.errors().errors().isEmpty());
    }

    @Test
    void emptyFunctionDocumentExplainsThatVanillaShipsNoFunctions() throws Exception {
        Path pack = temp.resolve("empty-pack");
        Files.createDirectories(pack.resolve("data"));
        Path output = temp.resolve("empty-output");

        ExtractorConfig config = ExtractorConfig.builder(output).prettyPrint(true).build();
        ExtractorContext context = ExtractorContext.builder()
                .config(config)
                .datapackRoot(pack)
                .build();

        assertEquals(0, new FunctionExtractor().extract(context));

        JsonObject document = TestSupport.object(output.resolve("functions/minecraft.json"));
        assertEquals(0, document.get("function_count").getAsInt());
        assertEquals(0, document.getAsJsonArray("data").size());
        assertEquals(0, document.get("tag_call_count").getAsInt());
        assertFalse(document.get("effective_tag_expansion_available").getAsBoolean());
        assertTrue(document.getAsJsonArray("tag_expansion_differences").isEmpty());
        assertFalse(document.get("vanilla_provides").getAsBoolean());
        assertTrue(document.get("note").getAsString().contains(".mcfunction"));
    }

    /**
     * Dangling references and cycles have to reach the run report, not only the resolver.
     *
     * <p>The extractor still writes the tag document so a consumer can inspect it; the problems are
     * reported as warnings, because a third-party pack may legitimately carry a broken optional
     * reference while the rest of the extraction remains usable.
     */
    @Test
    void reportsDanglingReferencesAndCyclesInTheDocumentAndRunReport() throws Exception {
        Path pack = temp.resolve("broken-pack");
        Path tags = pack.resolve("data/demo/tags/function");
        Files.createDirectories(tags);
        Files.writeString(tags.resolve("broken.json"), """
                {"values":["demo:missing","#demo:cycle_a"]}
                """, StandardCharsets.UTF_8);
        Files.writeString(tags.resolve("cycle_a.json"), """
                {"values":["#demo:cycle_b"]}
                """, StandardCharsets.UTF_8);
        Files.writeString(tags.resolve("cycle_b.json"), """
                {"values":["#demo:cycle_a"]}
                """, StandardCharsets.UTF_8);

        Path output = temp.resolve("broken-output");
        ExtractorConfig config = ExtractorConfig.builder(output).prettyPrint(true).build();
        ExtractorContext context = ExtractorContext.builder()
                .config(config)
                .datapackRoot(pack)
                .build();

        assertEquals(3, new FunctionTagExtractor().extract(context));

        JsonObject tagsDocument = TestSupport.object(output.resolve("tags/functions.json"));
        assertEquals(3, tagsDocument.get("tag_count").getAsInt());
        assertEquals(1, tagsDocument.get("missing_reference_count").getAsInt());
        assertEquals(3, tagsDocument.get("cyclic_tag_count").getAsInt());
        assertEquals(3, tagsDocument.get("broken_tag_count").getAsInt());
        assertFalse(tagsDocument.get("all_references_resolve").getAsBoolean());

        JsonObject broken = entry(tagsDocument, "demo:broken");
        assertEquals("demo:missing",
                broken.getAsJsonArray("missing_references").get(0).getAsString());
        assertFalse(broken.getAsJsonArray("references").get(0).getAsJsonObject()
                .get("exists").getAsBoolean());
        assertTrue(broken.get("cyclic").getAsBoolean());

        List<String> warningTypes = context.errors().warnings().stream()
                .map(issue -> issue.type()).toList();
        assertTrue(warningTypes.contains("MISSING_FUNCTION_REFERENCE"),
                "warnings: " + context.errors().warnings());
        assertTrue(warningTypes.contains("FUNCTION_TAG_CYCLE"),
                "warnings: " + context.errors().warnings());
        assertTrue(context.errors().errors().isEmpty(),
                "a broken data pack must not fail the extractor");
    }

    @Test
    void normalisesLineEndingsAndCountsCommandsCommentsAndMacros() {
        String content = FunctionExtractor.normalize("say a\r\n# note\r\n\r\n$say $(name)\r\n");

        assertEquals("say a\n# note\n\n$say $(name)\n", content);
        FunctionExtractor.Stats stats = FunctionExtractor.stats(content);
        assertEquals(4, stats.lines());
        assertEquals(2, stats.commands());
        assertEquals(1, stats.comments());
        assertEquals(1, stats.blanks());
        assertEquals(1, stats.macros());
    }

    private ExtractorContext context(Path output, VanillaDataLoader data) {
        ExtractorConfig config = ExtractorConfig.builder(output).prettyPrint(true).build();
        return ExtractorContext.builder()
                .config(config)
                .pack(data.pack())
                .functionTags(data.functionTags())
                .registryAccess(data.registryAccess())
                .build();
    }

    private static JsonObject entry(JsonObject document, String id) {
        for (var element : document.getAsJsonArray("data")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("id").getAsString().equals(id)) {
                return entry;
            }
        }
        throw new AssertionError("missing function " + id + " in " + document);
    }

    /**
     * A two-pack fixture whose {@code demo:load} tag is merged across packs.
     *
     * <p>The lower pack adds {@code demo:a} to the tag and loops back through {@code demo:entry}.
     * The upper pack only adds the leaf {@code demo:b}, so the loop exists only in the game's merged
     * view. {@code demo:replace} is marked {@code replace: true} by the upper pack; its lower member
     * must stay out of the call graph even though its source file is still loaded.
     */
    private Path mergedTagCyclePack(String name, boolean upper) throws IOException {
        Path pack = temp.resolve(name);
        Path functions = pack.resolve("data/demo/function");
        Path tags = pack.resolve("data/demo/tags/function");
        Files.createDirectories(functions);
        Files.createDirectories(tags);
        Files.writeString(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":121,"description":"Terila merged function tag cycle test"}}
                """, StandardCharsets.UTF_8);

        if (upper) {
            Files.writeString(functions.resolve("b.mcfunction"), "say b\n", StandardCharsets.UTF_8);
            Files.writeString(functions.resolve("replace_b.mcfunction"), "say b\n",
                    StandardCharsets.UTF_8);
            Files.writeString(tags.resolve("load.json"), """
                    {"values":["demo:b"]}
                    """, StandardCharsets.UTF_8);
            Files.writeString(tags.resolve("replace.json"), """
                    {"replace":true,"values":["demo:replace_b"]}
                    """, StandardCharsets.UTF_8);
        } else {
            Files.writeString(functions.resolve("entry.mcfunction"),
                    "function #demo:load\n", StandardCharsets.UTF_8);
            Files.writeString(functions.resolve("a.mcfunction"),
                    "function demo:entry\n", StandardCharsets.UTF_8);
            Files.writeString(functions.resolve("replace_entry.mcfunction"),
                    "function #demo:replace\n", StandardCharsets.UTF_8);
            Files.writeString(functions.resolve("replace_a.mcfunction"),
                    "function demo:replace_entry\n", StandardCharsets.UTF_8);
            Files.writeString(tags.resolve("load.json"), """
                    {"values":["demo:a"]}
                    """, StandardCharsets.UTF_8);
            Files.writeString(tags.resolve("replace.json"), """
                    {"values":["demo:replace_a"]}
                    """, StandardCharsets.UTF_8);
        }
        return pack;
    }

    /**
     * A two-function pack and one function tag.
     *
     * <p>{@code pack_format} is the 26.3 data pack major version; writing it here rather than reading
     * {@link com.terila.extractor.core.ExtractorVersion} keeps the fixture explicit about the format
     * it exercises.
     */
    private Path functionPack() throws IOException {
        Path pack = temp.resolve("pack");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":121,"description":"Terila function extraction test"}}
                """, StandardCharsets.UTF_8);

        Path functions = pack.resolve("data/demo/function");
        Files.createDirectories(functions.resolve("sub"));
        Files.writeString(functions.resolve("tick.mcfunction"),
                "# the server tick loop\r\nsay hello\r\n\r\n$say $(who)\r\n", StandardCharsets.UTF_8);
        Files.writeString(functions.resolve("sub/nested.mcfunction"),
                "function demo:tick\n", StandardCharsets.UTF_8);

        Path tags = pack.resolve("data/demo/tags/function");
        Files.createDirectories(tags);
        Files.writeString(tags.resolve("load.json"), """
                {"values":["demo:tick"]}
                """, StandardCharsets.UTF_8);
        Files.writeString(tags.resolve("all.json"), """
                {"values":["#demo:load"]}
                """, StandardCharsets.UTF_8);
        return pack;
    }
}

