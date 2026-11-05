package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Parses function tag files and resolves the function graph they describe.
 *
 * <p>Function tags are not a registry: the game reads them from
 * {@code data/<namespace>/tags/function}, and a value may name either a function or another tag
 * prefixed with {@code #}. That means a tag file can look valid while pointing at a function that
 * does not exist, or at a nested tag that was renamed or deleted. This class turns the raw files into
 * a resolved view so the extractor can report those dangling references instead of copying them
 * silently.
 *
 * <p>Resolution follows the same rules the game applies:
 *
 * <ul>
 *   <li>function ids and tag ids without a namespace default to {@code minecraft};</li>
 *   <li>{@code #namespace:path} references another tag and contributes that tag's functions;</li>
 *   <li>a reference marked {@code "required": false} is allowed to be missing;</li>
 *   <li>nested tags are flattened, and a cycle is reported instead of recursing forever.</li>
 * </ul>
 *
 * <p>Only the references are interpreted. The original {@code values} array is kept untouched, so a
 * consumer can still see exactly what the pack declared.
 */
public final class FunctionTagGraph {

    /** What a tag value points at. */
    public enum Kind {
        FUNCTION,
        TAG;

        /** Lower-case name used in the JSON output and in warning messages. */
        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One value of a tag.
     *
     * @param id       canonical identifier, always with a namespace.
     * @param kind     whether the value names a function or another tag.
     * @param required whether a missing target is an error or explicitly optional.
     */
    public record Reference(String id, Kind kind, boolean required) {
    }

    /**
     * A parsed tag.
     *
     * @param id         canonical tag identifier.
     * @param values     the original {@code values} member, preserved verbatim.
     * @param replace    the tag's {@code replace} flag.
     * @param references the values interpreted as references.
     */
    public record Tag(String id, JsonElement values, boolean replace, List<Reference> references) {
    }

    /**
     * The transitive result of resolving one tag.
     *
     * @param functions functions reachable from the tag, in declaration order, without duplicates.
     * @param missing   required references that could not be resolved, including nested ones.
     * @param cycles    cycle edges such as {@code demo:a -> demo:b}; empty when no cycle was found.
     */
    public record Resolution(List<String> functions, List<Reference> missing, List<String> cycles) {

        /** Whether resolution encountered a cycle. */
        public boolean cyclic() {
            return !cycles.isEmpty();
        }
    }

    private final Map<String, Tag> tags;
    private final Set<String> functions;

    private FunctionTagGraph(Map<String, Tag> tags, Set<String> functions) {
        this.tags = Collections.unmodifiableMap(new TreeMap<>(tags));
        this.functions = Set.copyOf(functions);
    }

    /**
     * Parses every function tag file in a pack.
     *
     * @param documents raw tag files keyed by canonical identifier.
     * @param functions every function source in the same pack, keyed by canonical identifier.
     * @return the parsed graph.
     */
    public static FunctionTagGraph of(Map<String, JsonElement> documents, Set<String> functions) {
        Map<String, Tag> parsed = new TreeMap<>();
        for (Map.Entry<String, JsonElement> document : documents.entrySet()) {
            JsonElement body = document.getValue();
            JsonElement values = valuesOf(body);
            parsed.put(document.getKey(),
                    new Tag(document.getKey(), values, replaceOf(body), referencesOf(values)));
        }
        return new FunctionTagGraph(parsed, functions);
    }

    /** Every tag, sorted by identifier. */
    public List<Tag> tags() {
        return List.copyOf(tags.values());
    }

    /** A parsed tag, or {@code null} when the pack does not declare it. */
    public Tag tag(String id) {
        return tags.get(id);
    }

    /** Number of function sources available to the graph. */
    public int functionCount() {
        return functions.size();
    }

    /** Whether a reference points at something that exists in this pack. */
    public boolean exists(Reference reference) {
        return reference.kind() == Kind.FUNCTION
                ? functions.contains(reference.id())
                : tags.containsKey(reference.id());
    }

    /**
     * Resolves one tag transitively.
     *
     * <p>Each call starts from a clean traversal state. Tags are small, and the alternative — caching
     * one resolution per tag — would have to distinguish a cache entry made while a cycle was still
     * open, which is exactly the case that is hardest to get right.
     *
     * @param tag tag to resolve.
     * @return its functions, missing required references and any cycle edges.
     */
    public Resolution resolve(Tag tag) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        LinkedHashSet<Reference> missing = new LinkedHashSet<>();
        LinkedHashSet<String> cycles = new LinkedHashSet<>();
        visit(tag.id(), new LinkedHashSet<>(), new LinkedHashSet<>(), resolved, missing, cycles);
        return new Resolution(List.copyOf(resolved), List.copyOf(missing), List.copyOf(cycles));
    }

    private void visit(String id, Set<String> stack, Set<String> done, Set<String> resolved,
                       Set<Reference> missing, Set<String> cycles) {
        if (!stack.add(id)) {
            return;
        }
        if (!done.add(id)) {
            stack.remove(id);
            return;
        }

        Tag tag = tags.get(id);
        if (tag == null) {
            stack.remove(id);
            return;
        }

        for (Reference reference : tag.references()) {
            if (reference.kind() == Kind.FUNCTION) {
                if (functions.contains(reference.id())) {
                    resolved.add(reference.id());
                } else if (reference.required()) {
                    missing.add(reference);
                }
                continue;
            }

            if (!tags.containsKey(reference.id())) {
                if (reference.required()) {
                    missing.add(reference);
                }
                continue;
            }

            // A tag on the current stack closes a cycle. Recording the edge from the current tag
            // makes the warning actionable without having to reproduce the whole traversal.
            if (stack.contains(reference.id())) {
                cycles.add(id + " -> " + reference.id());
                continue;
            }

            if (!done.contains(reference.id())) {
                visit(reference.id(), stack, done, resolved, missing, cycles);
            }
        }

        stack.remove(id);
    }

    /** The original {@code values} array, or the raw body for a non-standard tag file. */
    private static JsonElement valuesOf(JsonElement body) {
        if (body == null) {
            return new JsonArray();
        }
        if (!body.isJsonObject()) {
            return body;
        }
        JsonElement values = body.getAsJsonObject().get("values");
        return values != null ? values : new JsonArray();
    }

    private static boolean replaceOf(JsonElement body) {
        if (body == null || !body.isJsonObject()) {
            return false;
        }
        JsonElement replace = body.getAsJsonObject().get("replace");
        return replace != null && replace.isJsonPrimitive()
                && replace.getAsJsonPrimitive().isBoolean() && replace.getAsBoolean();
    }

    private static List<Reference> referencesOf(JsonElement values) {
        if (!values.isJsonArray()) {
            return List.of();
        }

        List<Reference> references = new ArrayList<>();
        for (JsonElement value : values.getAsJsonArray()) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                add(references, value.getAsString(), true);
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                JsonElement id = object.get("id");
                if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                    continue;
                }
                boolean required = object.has("required")
                        ? booleanOf(object.get("required"), true) : true;
                add(references, id.getAsString(), required);
            }
        }
        return List.copyOf(references);
    }

    private static boolean booleanOf(JsonElement element, boolean fallback) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean() ? element.getAsBoolean() : fallback;
    }

    private static void add(List<Reference> references, String raw, boolean required) {
        String value = raw.trim();
        boolean tag = value.startsWith("#");
        if (tag) {
            value = value.substring(1).trim();
        }
        if (value.isEmpty()) {
            return;
        }
        if (!value.contains(":")) {
            value = "minecraft:" + value;
        }
        references.add(new Reference(value, tag ? Kind.TAG : Kind.FUNCTION, required));
    }
}

