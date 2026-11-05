package com.terila.extractor.extractors.translations.model;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.core.ExtractorContext;

import java.util.Map;

/**
 * One language file.
 *
 * @param source            name of the language file it was read from.
 * @param entries           translation key to resolved text, sorted by key.
 * @param skippedNonStrings keys whose value was not a plain string (for example the structured
 *                          {@code removed}/{@code renamed} lists in {@code deprecated.json}).
 */
public record TranslationData(String source, Map<String, String> entries, int skippedNonStrings) {

    /** Document of the shape {@code {"_meta": {...}, "data": {key: value}}}. */
    public JsonObject toJson(ExtractorContext context, String extractorName) {
        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(extractorName, entries.size()));
        root.addProperty("source", source);
        if (skippedNonStrings > 0) {
            root.addProperty("skipped_non_string_values", skippedNonStrings);
        }

        JsonObject data = new JsonObject();
        entries.forEach(data::addProperty);
        root.add("data", data);
        return root;
    }
}
