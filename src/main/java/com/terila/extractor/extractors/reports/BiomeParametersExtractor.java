package com.terila.extractor.extractors.reports;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ReportReader;

import java.io.IOException;
import java.util.Map;

/**
 * Writes {@code worldgen/biome_parameters.json}: the multi-noise biome parameter lists.
 *
 * <p>These are the temperature/humidity/continentalness tables that decide which biome a given
 * world position resolves to. They are generated from code rather than shipped as datapack files, so
 * the generator's {@code reports/biome_parameters/} is the only way to read them; the runtime only
 * exposes them through the {@code minecraft:multi_noise_biome_source_parameter_list} registry, which
 * is dumped separately from the loaded packs.
 */
public final class BiomeParametersExtractor extends AbstractReportExtractor {

    private static final String DIRECTORY = "biome_parameters";

    public BiomeParametersExtractor() {
        super("biome_parameters", DataType.WORLDGEN, "worldgen/biome_parameters.json", DIRECTORY);
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        ReportReader reports = reports(context);
        if (reports == null) {
            return 0;
        }

        // The parameter lists are namespaced one directory deep: biome_parameters/minecraft/*.json.
        Map<String, JsonElement> files = reports.readTree(DIRECTORY);
        if (files.isEmpty()) {
            context.errors().warning("REPORT_EMPTY", name(), DIRECTORY,
                    "reports/" + DIRECTORY + " contains no parameter lists");
            return 0;
        }

        JsonObject data = new JsonObject();
        int parameterCount = 0;

        for (Map.Entry<String, JsonElement> file : files.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.add("values", file.getValue());
            data.add(file.getKey(), entry);

            // A parameter list is an array of {biome, parameters} records.
            JsonElement values = file.getValue();
            if (values.isJsonArray()) {
                parameterCount += values.getAsJsonArray().size();
            } else if (values.isJsonObject() && values.getAsJsonObject().has("values")) {
                JsonElement nested = values.getAsJsonObject().get("values");
                if (nested.isJsonArray()) {
                    parameterCount += nested.getAsJsonArray().size();
                }
            }
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.addProperty("source", reports.path(DIRECTORY).toString());
        root.addProperty("list_count", data.size());
        root.addProperty("parameter_count", parameterCount);
        root.add("data", data);
        write(context, root);
        return data.size();
    }
}
