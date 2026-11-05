package com.terila.extractor.extractors.tags;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.FunctionTagSource;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes {@code tags/effective_functions.json}: the function tags as the game resolves them.
 *
 * <p>{@code tags/functions.json} copies the raw tag files. That is useful for seeing what a pack
 * declares, but it cannot show what {@code /function #tag} will execute: the game merges entries from
 * stacked packs, applies each tag's {@code replace} flag, flattens nested {@code #tag} references and
 * drops missing entries. This extractor reads the resulting view from
 * {@link FunctionTagSource}, which is backed by the same function library the server uses.
 *
 * <p>The document is deliberately separate from the raw one. A consumer can compare the two when a
 * pack overrides a tag, and neither has to guess which layer a member came from.
 */
public final class EffectiveFunctionTagExtractor extends AbstractExtractor {

    public EffectiveFunctionTagExtractor() {
        super("effective_function_tags", DataType.TAGS, "tags/effective_functions.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        FunctionTagSource source = context.functionTags().orElse(null);
        if (source == null) {
            context.errors().warning("FUNCTION_TAGS_UNAVAILABLE", name(), null,
                    "The game function library is unavailable; effective function tags were skipped");
            return 0;
        }

        List<Identifier> tagIds = new ArrayList<>();
        source.tagIds().forEach(tagIds::add);
        tagIds.sort(Comparator.comparing(Identifier::toString));

        JsonArray data = new JsonArray();
        int functionReferences = 0;
        for (Identifier tagId : tagIds) {
            Set<String> functions = new TreeSet<>();
            source.functions(tagId).forEach(function -> functions.add(function.toString()));

            JsonArray functionArray = new JsonArray();
            functions.forEach(functionArray::add);
            functionReferences += functions.size();

            JsonObject tag = new JsonObject();
            tag.addProperty("id", tagId.toString());
            tag.addProperty("function_count", functions.size());
            tag.add("functions", functionArray);
            data.add(tag);
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.addProperty("source", "minecraft:function-tag-runtime");
        root.addProperty("function_count", source.functionCount());
        root.addProperty("tag_count", data.size());
        root.addProperty("function_reference_count", functionReferences);
        root.addProperty("vanilla_provides", !data.isEmpty());
        if (data.isEmpty()) {
            root.addProperty("note", "Vanilla Minecraft ships no functions or function tags; a data "
                    + "pack may add both, and this document then carries the game's merged view");
        }
        root.add("data", data);
        write(context, root);
        return data.size();
    }
}

