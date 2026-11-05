package com.terila.extractor.extractors.reports;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ReportReader;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes {@code protocol/json_rpc.json}: the JSON-RPC API surface.
 *
 * <p>26.3 exposes a JSON-RPC API alongside the packet protocol: the server can be driven over a
 * named method table instead of raw packets. Two registries back it
 * ({@code minecraft:incoming_rpc_methods}, {@code minecraft:outgoing_rpc_methods}) and the generator
 * writes the whole schema to {@code reports/json-rpc-api-schema.json}. Both halves are emitted here,
 * so a client implementation has the methods and their request/response shapes in one place.
 */
public final class JsonRpcExtractor extends AbstractReportExtractor {

    private static final String SCHEMA = "json-rpc-api-schema.json";
    private static final String REGISTRIES = "registries.json";

    public JsonRpcExtractor() {
        super("json_rpc", DataType.PROTOCOL, "protocol/json_rpc.json", SCHEMA);
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        ReportReader reports = reports(context);
        if (reports == null) {
            return 0;
        }

        JsonElement schema = reports.read(SCHEMA).orElse(null);
        JsonObject incoming = methodTable(reports, "minecraft:incoming_rpc_methods");
        JsonObject outgoing = methodTable(reports, "minecraft:outgoing_rpc_methods");

        if (schema == null && incoming.isEmpty() && outgoing.isEmpty()) {
            context.errors().warning("REPORT_EMPTY", name(), SCHEMA,
                    "no JSON-RPC report found; the JSON-RPC API was skipped");
            return 0;
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), incoming.size() + outgoing.size()));
        root.addProperty("source", reports.path(SCHEMA).toString());
        root.addProperty("incoming_method_count", incoming.size());
        root.addProperty("outgoing_method_count", outgoing.size());
        root.add("incoming_methods", incoming);
        root.add("outgoing_methods", outgoing);
        if (schema != null) {
            root.add("schema", schema);
        }
        write(context, root);
        return incoming.size() + outgoing.size();
    }

    /** Flattens one RPC registry out of {@code registries.json} into {@code name -> protocol id}. */
    private static JsonObject methodTable(ReportReader reports, String registryId) {
        JsonObject table = new JsonObject();
        JsonElement parsed = reports.read(REGISTRIES).orElse(null);
        if (parsed == null || !parsed.isJsonObject()) {
            return table;
        }
        JsonElement registry = parsed.getAsJsonObject().get(registryId);
        if (registry == null || !registry.isJsonObject()) {
            return table;
        }
        JsonElement entries = registry.getAsJsonObject().get("entries");
        if (entries == null || !entries.isJsonObject()) {
            return table;
        }
        for (Map.Entry<String, JsonElement> entry
                : new TreeMap<>(entries.getAsJsonObject().asMap()).entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject() && value.getAsJsonObject().has("protocol_id")) {
                table.addProperty(entry.getKey(), value.getAsJsonObject().get("protocol_id").getAsInt());
            } else if (value.isJsonPrimitive()) {
                table.addProperty(entry.getKey(), value.getAsInt());
            }
        }
        return table;
    }
}
