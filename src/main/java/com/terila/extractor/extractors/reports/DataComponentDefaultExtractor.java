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
 * Writes {@code items/component_defaults.json}: the default data components of every item.
 *
 * <p>An item on the wire is an identifier plus a component patch. The game generates one report per
 * item containing the full component set a freshly crafted item carries, which is exactly the
 * baseline a client needs before applying the patch. 1658 separate files are merged into a single
 * document, and a histogram of component usage is added so the shape of the data is visible without
 * reading a megabyte of JSON.
 */
public final class DataComponentDefaultExtractor extends AbstractReportExtractor {

    private static final String DIRECTORY = "minecraft/components/item";

    public DataComponentDefaultExtractor() {
        super("item_component_defaults", DataType.ITEMS, "items/component_defaults.json",
                DIRECTORY);
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        ReportReader reports = reports(context);
        if (reports == null) {
            return 0;
        }

        Map<String, JsonElement> files = reports.readTree(DIRECTORY);
        if (files.isEmpty()) {
            context.errors().warning("REPORT_EMPTY", name(), DIRECTORY,
                    "reports/" + DIRECTORY + " contains no item component reports");
            return 0;
        }

        JsonObject data = new JsonObject();
        JsonObject usage = new JsonObject();
        int componentTotal = 0;

        for (Map.Entry<String, JsonElement> file : files.entrySet()) {
            JsonElement body = file.getValue();
            if (!body.isJsonObject()) {
                continue;
            }
            JsonElement components = body.getAsJsonObject().get("components");
            if (components == null || !components.isJsonObject()) {
                continue;
            }

            String itemId = file.getKey().contains(":") ? file.getKey()
                    : "minecraft:" + file.getKey();
            data.add(itemId, components);

            for (String component : components.getAsJsonObject().keySet()) {
                int previous = usage.has(component) ? usage.get(component).getAsInt() : 0;
                usage.addProperty(component, previous + 1);
                componentTotal++;
            }
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.addProperty("source", reports.path(DIRECTORY).toString());
        root.addProperty("item_count", data.size());
        root.addProperty("component_assignments", componentTotal);
        root.addProperty("distinct_components", usage.size());
        JsonObject sortedUsage = new JsonObject();
        new TreeMap<>(usage.asMap()).forEach(sortedUsage::add);
        root.add("component_usage", sortedUsage);
        root.add("data", data);
        write(context, root);
        return data.size();
    }
}
