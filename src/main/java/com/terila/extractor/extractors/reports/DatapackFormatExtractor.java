package com.terila.extractor.extractors.reports;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ReportReader;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes {@code registries/datapack_formats.json}: which registries a data pack may define.
 *
 * <p>{@code reports/datapack.json} is the game's own description of its data pack surface: for each
 * registry it says whether entries can appear as files ({@code elements}), whether tags are
 * accepted, and whether the registry is part of the stable API. The two directories that are not
 * registries ({@code function}, {@code structure}) are listed too.
 *
 * <p>This is the file to consult before writing a data pack: it is the difference between "this
 * registry exists" and "a data pack can put a file in it".
 */
public final class DatapackFormatExtractor extends AbstractReportExtractor {

    public DatapackFormatExtractor() {
        super("datapack_formats", DataType.MISC, "registries/datapack_formats.json", "datapack.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        ReportReader reports = reports(context);
        if (reports == null) {
            return 0;
        }

        JsonElement parsed = reports.read("datapack.json").orElse(null);
        if (parsed == null || !parsed.isJsonObject()) {
            context.errors().warning("REPORT_EMPTY", name(), "datapack.json",
                    "reports/datapack.json is missing or not a JSON object");
            return 0;
        }

        JsonObject source = parsed.getAsJsonObject();
        JsonObject registries = new JsonObject();
        JsonArray elementRegistries = new JsonArray();
        JsonArray tagRegistries = new JsonArray();
        JsonArray unstableRegistries = new JsonArray();

        JsonElement rawRegistries = source.get("registries");
        if (rawRegistries != null && rawRegistries.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : new TreeMap<>(rawRegistries.getAsJsonObject().asMap()).entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject flags = entry.getValue().getAsJsonObject();
                JsonObject out = new JsonObject();
                boolean elements = flag(flags, "elements");
                boolean tags = flag(flags, "tags");
                boolean stable = flag(flags, "stable");
                out.addProperty("elements", elements);
                out.addProperty("tags", tags);
                out.addProperty("stable", stable);
                registries.add(entry.getKey(), out);

                if (elements) {
                    elementRegistries.add(entry.getKey());
                }
                if (tags) {
                    tagRegistries.add(entry.getKey());
                }
                if (!stable) {
                    unstableRegistries.add(entry.getKey());
                }
            }
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), registries.size()));
        root.addProperty("source", reports.path("datapack.json").toString());
        root.addProperty("registry_count", registries.size());
        root.addProperty("data_pack_version", com.terila.extractor.core.ExtractorVersion.DATA_PACK_VERSION);
        if (source.has("others")) {
            root.add("other_directories", source.get("others"));
        }
        root.add("element_registries", elementRegistries);
        root.add("tag_registries", tagRegistries);
        root.add("unstable_registries", unstableRegistries);
        root.add("registries", registries);
        write(context, root);
        return registries.size();
    }

    private static boolean flag(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }
}
