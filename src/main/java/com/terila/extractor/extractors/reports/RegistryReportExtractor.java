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
 * Writes {@code registries/report.json}: every registry the server declares, with the protocol id of
 * each entry.
 *
 * <p>This is the flattening of {@code reports/registries.json}. Where the raw report nests each entry
 * in an object, this puts the id straight next to the protocol id:
 *
 * <pre>
 * { "minecraft:block": { "protocol_id": true, "default": "minecraft:air",
 *                        "entry_count": 1286,
 *                        "entries": { "minecraft:acacia_button": 499, ... } } }
 * </pre>
 *
 * <p>It covers all 95 registries the server knows — including the ones that never appear in the
 * runtime registry access, such as the argument parsers and the new RPC method tables — so it is the
 * reference for "which numeric id does this name have on the wire".
 */
public final class RegistryReportExtractor extends AbstractReportExtractor {

    public RegistryReportExtractor() {
        super("registry_report", DataType.MISC, "registries/report.json", "registries.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        ReportReader reports = reports(context);
        if (reports == null) {
            return 0;
        }

        JsonElement parsed = reports.read("registries.json").orElse(null);
        if (parsed == null || !parsed.isJsonObject()) {
            context.errors().warning("REPORT_EMPTY", name(), "registries.json",
                    "reports/registries.json is missing or not a JSON object");
            return 0;
        }

        JsonObject registries = new JsonObject();
        JsonArray index = new JsonArray();
        int totalEntries = 0;

        for (Map.Entry<String, JsonElement> entry
                : new TreeMap<>(parsed.getAsJsonObject().asMap()).entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject registry = entry.getValue().getAsJsonObject();
            JsonObject out = new JsonObject();

            if (registry.has("default")) {
                out.addProperty("default", registry.get("default").getAsString());
            }
            if (registry.has("protocol_id")) {
                out.add("protocol_id", registry.get("protocol_id"));
            }

            JsonObject entries = new JsonObject();
            int count = 0;
            int lowest = Integer.MAX_VALUE;
            int highest = Integer.MIN_VALUE;

            JsonElement raw = registry.get("entries");
            if (raw != null && raw.isJsonObject()) {
                for (Map.Entry<String, JsonElement> value : new TreeMap<>(raw.getAsJsonObject().asMap())
                        .entrySet()) {
                    int id = -1;
                    if (value.getValue().isJsonObject()
                            && value.getValue().getAsJsonObject().has("protocol_id")) {
                        id = value.getValue().getAsJsonObject().get("protocol_id").getAsInt();
                    } else if (value.getValue().isJsonPrimitive()) {
                        id = value.getValue().getAsInt();
                    }
                    entries.addProperty(value.getKey(), id);
                    if (id >= 0) {
                        lowest = Math.min(lowest, id);
                        highest = Math.max(highest, id);
                    }
                    count++;
                }
            }

            out.addProperty("entry_count", count);
            if (count > 0 && lowest != Integer.MAX_VALUE) {
                out.addProperty("min_protocol_id", lowest);
                out.addProperty("max_protocol_id", highest);
            }
            out.add("entries", entries);
            registries.add(entry.getKey(), out);

            JsonObject summary = new JsonObject();
            summary.addProperty("id", entry.getKey());
            summary.addProperty("entry_count", count);
            index.add(summary);
            totalEntries += count;
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), totalEntries));
        root.addProperty("source", reports.path("registries.json").toString());
        root.addProperty("registry_count", registries.size());
        root.addProperty("entry_count", totalEntries);
        root.add("index", index);
        root.add("registries", registries);
        write(context, root);
        return totalEntries;
    }
}
