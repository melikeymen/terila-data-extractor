package com.terila.extractor.extractors.tags;

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
import com.terila.extractor.extractors.functions.FunctionExtractor;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes {@code tags/functions.json}.
 *
 * <p>Function tags have no runtime registry — they are only datapack files — so they are read
 * straight from the pack. Values keep their original {@code #nested} references, while the document
 * also resolves them: every existing function and nested tag is listed, every dangling reference is
 * reported, and cycles are named explicitly.
 *
 * <p>Vanilla ships no {@code .mcfunction} files at all, so {@code data/minecraft/tags/function} does
 * not exist and this document is empty by design. That is recorded explicitly, because an empty
 * array on its own is indistinguishable from a failed extraction.
 *
 * <p>When the game's function library is available, every tag is also compared with the view the
 * server would execute. This document is built from the winning pack file, so with several stacked
 * packs a tag that is merged (rather than replaced) declares fewer members than the game runs. Each
 * tag therefore carries {@code effective_functions} and {@code matches_effective}, and the root lists
 * {@code effective_differences}. Those differences are normal for a merge and are not warnings; they
 * make the gap visible without a manual join against {@code tags/effective_functions.json}.
 */
public final class FunctionTagExtractor extends AbstractExtractor {

    /** Directory inside {@code data/<namespace>/}. */
    static final String DIRECTORY = "tags/function";

    public FunctionTagExtractor() {
        super("function_tags", DataType.TAGS, "tags/functions.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        PackReader reader = context.pack().orElse(null);
        if (reader == null) {
            context.errors().warning("DATAPACK_UNAVAILABLE", name(), null,
                    "No data pack available; function tags were skipped");
            return 0;
        }

        Map<String, JsonElement> documents = reader.loadAll(DIRECTORY);
        Map<String, String> functions = reader.loadTextAll(
                FunctionExtractor.DIRECTORY, FunctionExtractor.EXTENSION);
        FunctionTagGraph graph = FunctionTagGraph.of(documents, functions.keySet());

        // The raw files only show the winning pack file per tag. Read the game's merged view once so
        // both the per-tag comparison and the root difference report use the same snapshot.
        FunctionTagSource effectiveSource = context.functionTags().orElse(null);
        Map<String, List<String>> effectiveByTag = new TreeMap<>();
        int effectiveReferenceCount = 0;
        if (effectiveSource != null) {
            List<Identifier> effectiveTagIds = new ArrayList<>();
            effectiveSource.tagIds().forEach(effectiveTagIds::add);
            effectiveTagIds.sort(Comparator.comparing(Identifier::toString));
            for (Identifier tagId : effectiveTagIds) {
                Set<String> members = new TreeSet<>();
                effectiveSource.functions(tagId).forEach(member -> members.add(member.toString()));
                effectiveByTag.put(tagId.toString(), List.copyOf(members));
                effectiveReferenceCount += members.size();
            }
        }

        JsonArray data = new JsonArray();
        JsonArray effectiveDifferences = new JsonArray();
        int mismatchedTagCount = 0;
        int referenceCount = 0;
        int functionReferenceCount = 0;
        int tagReferenceCount = 0;
        int resolvedFunctionCount = 0;
        int missingReferenceCount = 0;
        int optionalMissingReferenceCount = 0;
        int brokenTagCount = 0;
        int cyclicTagCount = 0;

        for (FunctionTagGraph.Tag tag : graph.tags()) {
            FunctionTagGraph.Resolution resolution = graph.resolve(tag);
            JsonArray references = new JsonArray();
            Set<String> directFunctions = new LinkedHashSet<>();
            Set<String> nestedTags = new LinkedHashSet<>();
            List<String> missing = new ArrayList<>();
            List<String> optionalMissing = new ArrayList<>();

            for (FunctionTagGraph.Reference reference : tag.references()) {
                boolean exists = graph.exists(reference);
                referenceCount++;
                if (reference.kind() == FunctionTagGraph.Kind.FUNCTION) {
                    functionReferenceCount++;
                    if (exists) {
                        directFunctions.add(reference.id());
                    }
                } else {
                    tagReferenceCount++;
                    if (exists) {
                        nestedTags.add(reference.id());
                    }
                }

                JsonObject entry = new JsonObject();
                entry.addProperty("id", reference.id());
                entry.addProperty("type", reference.kind().jsonName());
                entry.addProperty("required", reference.required());
                entry.addProperty("exists", exists);
                references.add(entry);

                if (!exists) {
                    if (reference.required()) {
                        missing.add(reference.id());
                    } else {
                        optionalMissing.add(reference.id());
                    }
                }
            }

            missingReferenceCount += missing.size();
            optionalMissingReferenceCount += optionalMissing.size();
            resolvedFunctionCount += resolution.functions().size();

            List<String> effective = effectiveByTag.getOrDefault(tag.id(), List.of());
            boolean matchesEffective = true;
            if (effectiveSource != null) {
                Set<String> declared = new TreeSet<>(resolution.functions());
                Set<String> effectiveMembers = new TreeSet<>(effective);
                matchesEffective = declared.equals(effectiveMembers);
                if (!matchesEffective) {
                    mismatchedTagCount++;
                    effectiveDifferences.add(difference(tag.id(), declared, effectiveMembers));
                }
            }

            boolean cyclic = resolution.cyclic();
            if (!missing.isEmpty() || cyclic) {
                brokenTagCount++;
            }
            if (cyclic) {
                cyclicTagCount++;
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("id", tag.id());
            entry.add("values", tag.values());
            entry.addProperty("replace", tag.replace());
            entry.add("references", references);
            entry.add("direct_functions", strings(directFunctions));
            entry.add("nested_tags", strings(nestedTags));
            entry.add("resolved_functions", strings(resolution.functions()));
            entry.addProperty("resolved_function_count", resolution.functions().size());
            entry.add("missing_references", strings(missing));
            entry.add("optional_missing_references", strings(optionalMissing));
            entry.addProperty("cyclic", cyclic);
            if (effectiveSource != null) {
                entry.add("effective_functions", strings(effective));
                entry.addProperty("effective_function_count", effective.size());
                entry.addProperty("matches_effective", matchesEffective);
            }
            data.add(entry);

            reportIssues(context, tag.id(), resolution);
        }

        if (effectiveSource != null) {
            // A tag the game reports without a raw file cannot happen with the current loader, but
            // comparing the union keeps the report honest if that ever changes.
            Set<String> declaredTagIds = new TreeSet<>();
            graph.tags().forEach(tag -> declaredTagIds.add(tag.id()));
            for (Map.Entry<String, List<String>> entry : effectiveByTag.entrySet()) {
                if (declaredTagIds.contains(entry.getKey())) {
                    continue;
                }
                mismatchedTagCount++;
                effectiveDifferences.add(difference(entry.getKey(), Set.of(),
                        new TreeSet<>(entry.getValue())));
            }
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.addProperty("source", "data/<namespace>/" + DIRECTORY);
        root.addProperty("function_count", graph.functionCount());
        root.addProperty("tag_count", data.size());
        root.addProperty("reference_count", referenceCount);
        root.addProperty("function_reference_count", functionReferenceCount);
        root.addProperty("tag_reference_count", tagReferenceCount);
        root.addProperty("resolved_function_reference_count", resolvedFunctionCount);
        root.addProperty("missing_reference_count", missingReferenceCount);
        root.addProperty("optional_missing_reference_count", optionalMissingReferenceCount);
        root.addProperty("broken_tag_count", brokenTagCount);
        root.addProperty("cyclic_tag_count", cyclicTagCount);
        root.addProperty("all_references_resolve", brokenTagCount == 0);
        if (effectiveSource != null) {
            root.addProperty("effective_view_available", true);
            root.addProperty("effective_tag_count", effectiveByTag.size());
            root.addProperty("effective_function_reference_count", effectiveReferenceCount);
            root.addProperty("mismatched_tag_count", mismatchedTagCount);
            root.addProperty("matches_effective_view", mismatchedTagCount == 0);
            root.add("effective_differences", effectiveDifferences);
        }
        root.addProperty("vanilla_provides", !documents.isEmpty());
        if (documents.isEmpty()) {
            root.addProperty("note", "Vanilla Minecraft ships no functions, so it defines no "
                    + "function tags; a data pack may add both");
        }
        root.add("data", data);
        write(context, root);
        return data.size();
    }

    /** One tag whose declared resolution differs from the game's merged view. */
    private static JsonObject difference(String id, Set<String> declared, Set<String> effective) {
        Set<String> onlyDeclared = new TreeSet<>(declared);
        onlyDeclared.removeAll(effective);
        Set<String> onlyEffective = new TreeSet<>(effective);
        onlyEffective.removeAll(declared);

        JsonObject difference = new JsonObject();
        difference.addProperty("id", id);
        difference.add("only_declared_functions", strings(onlyDeclared));
        difference.add("only_effective_functions", strings(onlyEffective));
        return difference;
    }

    /** Reports dangling references and cycles without aborting the extraction. */
    private void reportIssues(ExtractorContext context, String tagId,
                              FunctionTagGraph.Resolution resolution) {
        for (FunctionTagGraph.Reference reference : resolution.missing()) {
            String type = reference.kind() == FunctionTagGraph.Kind.FUNCTION
                    ? "MISSING_FUNCTION_REFERENCE"
                    : "MISSING_FUNCTION_TAG_REFERENCE";
            context.errors().warning(type, name(), tagId,
                    "Function tag references a missing " + reference.kind().jsonName()
                            + ": " + reference.id());
        }
        if (resolution.cyclic()) {
            context.errors().warning("FUNCTION_TAG_CYCLE", name(), tagId,
                    "Function tag references form a cycle: "
                            + String.join(", ", resolution.cycles()));
        }
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}

