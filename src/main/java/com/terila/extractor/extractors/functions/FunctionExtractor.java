package com.terila.extractor.extractors.functions;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.FunctionTagGraph;
import com.terila.extractor.core.FunctionTagSource;
import com.terila.extractor.core.PackReader;
import com.terila.extractor.util.HashUtil;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes {@code functions/minecraft.json}: every {@code .mcfunction} source in the loaded packs.
 *
 * <p>Functions are the one datapack data set that is not JSON. They live at
 * {@code data/<namespace>/function/<path>.mcfunction} and are plain command lists, so the JSON reader
 * cannot see them. This extractor reads them through {@link PackReader#loadTextAll}, which reaches the
 * vanilla pack and every stacked datapack with the game's own override rules.
 *
 * <p>Vanilla ships no functions, so the document is empty for a plain run and says so. A datapack may
 * add functions; the same document then carries their source exactly as the game loads it, plus the
 * line, command, comment and macro counts a consumer would otherwise have to compute. Function tags,
 * which reference these ids, are written separately to {@code tags/functions.json}.
 *
 * <p>The document also records the function calls found in each source. A call to a missing function
 * or function tag is written to {@code _errors.json} as a warning; macro targets are kept but not
 * validated, because their value is only known while the function runs. Calls through a function tag
 * are expanded to the tag's functions before the call graph is checked, so a recursive loop that
 * crosses a tag is still visible. When the loaded function library is available, that expansion uses
 * the game's merged view, so a member contributed by a lower pack takes part in the graph too. The
 * root records which view was used, how many tag calls expanded differently from the raw files, and
 * which called tags those differences belong to.
 */
public final class FunctionExtractor extends AbstractExtractor {

    /** Directory inside {@code data/<namespace>/}. */
    public static final String DIRECTORY = "function";

    /** Extension of a function source, including the dot. */
    public static final String EXTENSION = ".mcfunction";

    public FunctionExtractor() {
        super("functions", DataType.FUNCTIONS, "functions/minecraft.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        PackReader reader = context.pack().orElse(null);
        if (reader == null) {
            context.errors().warning("DATAPACK_UNAVAILABLE", name(), DIRECTORY,
                    "No data pack available; .mcfunction sources were skipped");
            return 0;
        }

        Map<String, String> sources = reader.loadTextAll(DIRECTORY, EXTENSION);
        Map<String, JsonElement> functionTags = reader.loadAll("tags/function");
        FunctionTagGraph tagGraph = FunctionTagGraph.of(functionTags, sources.keySet());

        // A raw tag file only shows the winning pack file. When the game's library is available, use
        // its merged, flattened members for call edges, so a lower-pack member still participates.
        FunctionTagSource effectiveSource = context.functionTags().orElse(null);
        Map<String, List<String>> effectiveTags = new HashMap<>();
        if (effectiveSource != null) {
            effectiveSource.tagIds().forEach(tagId -> effectiveTags.put(tagId.toString(),
                    effectiveSource.functions(tagId).stream()
                            .map(Identifier::toString)
                            .filter(sources::containsKey)
                            .toList()));
        }
        Map<String, List<String>> rawResolvedTags = new HashMap<>();

        JsonArray data = new JsonArray();
        Map<String, Set<String>> callEdges = new TreeMap<>();
        sources.keySet().forEach(id -> callEdges.put(id, new LinkedHashSet<>()));
        int totalLines = 0;
        int totalCommands = 0;
        int totalBytes = 0;
        int totalCalls = 0;
        int totalDynamicCalls = 0;
        int totalTagCalls = 0;
        Map<String, Integer> tagCallCounts = new TreeMap<>();
        int totalMissingCalls = 0;
        int brokenFunctions = 0;

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String raw = entry.getValue();
            String content = normalize(raw);
            Stats stats = stats(content);
            int sourceBytes = raw.getBytes(StandardCharsets.UTF_8).length;

            List<FunctionCallParser.Call> calls = FunctionCallParser.parse(content);
            JsonArray callArray = new JsonArray();
            Set<String> missingCalls = new LinkedHashSet<>();
            int dynamicCalls = 0;

            for (FunctionCallParser.Call call : calls) {
                boolean dynamic = call.dynamic();
                // A macro target cannot be resolved before the function runs; "exists" stays false
                // and the dynamic flag tells a consumer that this is unknown rather than missing.
                boolean exists = !dynamic && (call.kind() == FunctionCallParser.Kind.FUNCTION
                        ? sources.containsKey(call.target())
                        : functionTags.containsKey(call.target())
                                || effectiveTags.containsKey(call.target()));

                JsonObject callJson = new JsonObject();
                callJson.addProperty("target", call.target());
                callJson.addProperty("type", call.kind().jsonName());
                callJson.addProperty("line", call.line());
                callJson.addProperty("dynamic", dynamic);
                callJson.addProperty("exists", exists);
                callArray.add(callJson);

                if (dynamic) {
                    dynamicCalls++;
                } else {
                    if (call.kind() == FunctionCallParser.Kind.TAG) {
                        totalTagCalls++;
                    }
                    if (!exists) {
                        missingCalls.add(call.reference());
                        context.errors().warning(
                                call.kind() == FunctionCallParser.Kind.FUNCTION
                                        ? "MISSING_FUNCTION_CALL" : "MISSING_FUNCTION_TAG_CALL",
                                name(), entry.getKey(),
                                "Line " + call.line() + " calls a missing "
                                        + call.kind().jsonName() + ": " + call.reference());
                    } else if (call.kind() == FunctionCallParser.Kind.FUNCTION) {
                        callEdges.get(entry.getKey()).add(call.target());
                    } else {
                        List<String> rawTargets = rawResolvedTags.computeIfAbsent(
                                call.target(), id -> {
                                    FunctionTagGraph.Tag tag = tagGraph.tag(id);
                                    return tag == null ? List.of()
                                            : tagGraph.resolve(tag).functions();
                                });
                        List<String> targets = effectiveSource == null
                                ? rawTargets
                                : effectiveTags.getOrDefault(call.target(), List.of());
                        tagCallCounts.merge(call.target(), 1, Integer::sum);
                        callEdges.get(entry.getKey()).addAll(targets);
                    }
                }
            }

            JsonObject function = new JsonObject();
            function.addProperty("id", entry.getKey());
            function.addProperty("resource", resourcePath(entry.getKey()));
            function.addProperty("source_bytes", sourceBytes);
            function.addProperty("line_count", stats.lines());
            function.addProperty("command_count", stats.commands());
            function.addProperty("comment_count", stats.comments());
            function.addProperty("blank_line_count", stats.blanks());
            function.addProperty("macro_count", stats.macros());
            function.addProperty("call_count", calls.size());
            function.add("calls", callArray);
            function.add("missing_references", strings(missingCalls));
            function.addProperty("sha256", HashUtil.sha256(content));
            function.addProperty("content", content);
            data.add(function);

            totalLines += stats.lines();
            totalCommands += stats.commands();
            totalBytes += sourceBytes;
            totalCalls += calls.size();
            totalDynamicCalls += dynamicCalls;
            totalMissingCalls += missingCalls.size();
            if (!missingCalls.isEmpty()) {
                brokenFunctions++;
            }
        }

        List<FunctionCallGraph.Cycle> cycles = FunctionCallGraph.find(callEdges);
        int selfCalls = (int) cycles.stream().filter(FunctionCallGraph.Cycle::selfCall).count();
        int cyclicFunctions = cycles.stream()
                .mapToInt(cycle -> cycle.functions().size()).sum();
        TagExpansionReport tagExpansions = effectiveSource == null
                ? new TagExpansionReport(new JsonArray(), 0)
                : summarizeTagExpansions(tagCallCounts, rawResolvedTags, effectiveTags);

        for (FunctionCallGraph.Cycle cycle : cycles) {
            if (cycle.selfCall()) {
                context.errors().warning("FUNCTION_SELF_CALL", name(), cycle.functions().get(0),
                        "Function calls itself directly or through a function tag");
            } else {
                context.errors().warning("FUNCTION_CALL_CYCLE", name(), cycle.functions().get(0),
                        "Function call cycle among: " + String.join(", ", cycle.functions()));
            }
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), sources.size()));
        root.addProperty("source", "data/<namespace>/" + DIRECTORY + "/**/*" + EXTENSION);
        root.addProperty("function_count", sources.size());
        root.addProperty("total_line_count", totalLines);
        root.addProperty("total_command_count", totalCommands);
        root.addProperty("total_source_bytes", totalBytes);
        root.addProperty("call_count", totalCalls);
        root.addProperty("dynamic_call_count", totalDynamicCalls);
        root.addProperty("tag_call_count", totalTagCalls);
        root.addProperty("tag_expansion_mismatch_count", tagExpansions.mismatchCalls());
        root.addProperty("effective_tag_expansion_available", effectiveSource != null);
        root.add("tag_expansion_differences", tagExpansions.differences());
        root.addProperty("missing_reference_count", totalMissingCalls);
        root.addProperty("broken_function_count", brokenFunctions);
        root.addProperty("call_cycle_count", cycles.size());
        root.addProperty("self_call_count", selfCalls);
        root.addProperty("cyclic_function_count", cyclicFunctions);
        root.add("cycles", cyclesJson(cycles));
        root.addProperty("vanilla_provides", !sources.isEmpty());
        if (sources.isEmpty()) {
            root.addProperty("note", "Vanilla Minecraft ships no .mcfunction files; a data pack may "
                    + "add them to data/<namespace>/function/");
        }
        root.add("data", data);
        write(context, root);
        return sources.size();
    }

    /**
     * Normalises line endings so the same source hashes identically on every platform.
     *
     * <p>Only line endings change: command text, comments and trailing whitespace are preserved
     * verbatim, because a consumer may need to reproduce the function byte for byte.
     */
    static String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Counts the lines a function is made of, mirroring how the game parses it. */
    static Stats stats(String content) {
        if (content.isEmpty()) {
            return new Stats(0, 0, 0, 0, 0);
        }

        String[] lines = content.split("\n", -1);
        int limit = lines.length;
        // A trailing newline terminates the last line; it does not add an empty one.
        if (content.endsWith("\n")) {
            limit--;
        }

        int commands = 0;
        int comments = 0;
        int blanks = 0;
        int macros = 0;
        for (int i = 0; i < limit; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                blanks++;
            } else if (line.startsWith("#")) {
                comments++;
            } else {
                commands++;
                if (line.startsWith("$")) {
                    macros++;
                }
            }
        }
        return new Stats(limit, commands, comments, blanks, macros);
    }

    /**
     * Groups tag calls by the tag they used and keeps only the tags whose merged and raw expansions
     * differ. Members are sorted so the report does not depend on declaration order.
     */
    private static TagExpansionReport summarizeTagExpansions(
            Map<String, Integer> callCounts,
            Map<String, List<String>> rawTags,
            Map<String, List<String>> effectiveTags) {
        JsonArray differences = new JsonArray();
        int mismatchCalls = 0;
        for (Map.Entry<String, Integer> entry : callCounts.entrySet()) {
            String id = entry.getKey();
            List<String> raw = rawTags.getOrDefault(id, List.of());
            List<String> effective = effectiveTags.getOrDefault(id, List.of());
            if (sameMembers(raw, effective)) {
                continue;
            }

            Set<String> onlyRaw = new TreeSet<>(raw);
            onlyRaw.removeAll(effective);
            Set<String> onlyEffective = new TreeSet<>(effective);
            onlyEffective.removeAll(raw);

            JsonObject difference = new JsonObject();
            difference.addProperty("id", id);
            difference.addProperty("call_count", entry.getValue());
            difference.add("only_raw_functions", strings(onlyRaw));
            difference.add("only_effective_functions", strings(onlyEffective));
            differences.add(difference);
            mismatchCalls += entry.getValue();
        }
        return new TagExpansionReport(differences, mismatchCalls);
    }

    /** Whether two tag expansions contain the same functions, regardless of declaration order. */
    private static boolean sameMembers(List<String> left, List<String> right) {
        return new HashSet<>(left).equals(new HashSet<>(right));
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonArray cyclesJson(List<FunctionCallGraph.Cycle> cycles) {
        JsonArray array = new JsonArray();
        for (FunctionCallGraph.Cycle cycle : cycles) {
            JsonObject json = new JsonObject();
            json.add("functions", strings(cycle.functions()));
            json.addProperty("self_call", cycle.selfCall());
            array.add(json);
        }
        return array;
    }

    /** {@code demo:tick} → {@code data/demo/function/tick.mcfunction}. */
    private static String resourcePath(String id) {
        int separator = id.indexOf(':');
        String namespace = id.substring(0, separator);
        String path = id.substring(separator + 1);
        return "data/" + namespace + "/" + DIRECTORY + "/" + path + EXTENSION;
    }

    /**
     * The per-file counters.
     *
     * @param lines    number of lines, excluding the empty line a trailing newline would imply.
     * @param commands non-blank, non-comment lines, including macro lines.
     * @param comments lines whose first non-whitespace character is {@code #}.
     * @param blanks   empty or whitespace-only lines.
     * @param macros   command lines starting with {@code $}.
     */
    record Stats(int lines, int commands, int comments, int blanks, int macros) {
    }

    /**
     * The tag expansion detail carried by the document root.
     *
     * @param differences   one entry per called tag whose members differ between the two views.
     * @param mismatchCalls number of tag call sites covered by those entries.
     */
    private record TagExpansionReport(JsonArray differences, int mismatchCalls) {
    }
}

