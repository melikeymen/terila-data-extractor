package com.terila.extractor.extractors.reports;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ReportReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes {@code commands/report.json}: the vanilla command tree exactly as the game serialises it.
 *
 * <p>{@link com.terila.extractor.extractors.commands.CommandTreeExtractor} walks the live Brigadier
 * dispatcher, which is what a client stub needs at runtime. This extractor keeps the game's own
 * report as well: it is the canonical form the Minecraft wiki and every third party tool use, it
 * carries the argument {@code properties} (a resource key's registry, a string's greedy flag, a
 * number's bounds) and it is independent of how the dispatcher happens to be built in a given run.
 * Both files describe the same tree, so they double as a cross-check.
 */
public final class CommandReportExtractor extends AbstractReportExtractor {

    public CommandReportExtractor() {
        super("command_report", DataType.COMMANDS, "commands/report.json", "commands.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        ReportReader reports = reports(context);
        if (reports == null) {
            return 0;
        }

        Path file = reports.path("commands.json");
        if (!Files.isRegularFile(file)) {
            context.errors().warning("REPORT_EMPTY", name(), "commands.json",
                    "reports/commands.json is missing");
            return 0;
        }

        JsonElement parsed = reports.read("commands.json").orElse(null);
        if (parsed == null || !parsed.isJsonObject()) {
            context.errors().warning("REPORT_INVALID", name(), "commands.json",
                    "reports/commands.json is not a JSON object");
            return 0;
        }

        JsonObject tree = parsed.getAsJsonObject();
        int nodes = count(tree);
        int executables = countExecutable(tree);
        int arguments = countArguments(tree);

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), nodes));
        root.addProperty("source", file.toString());
        root.addProperty("node_count", nodes);
        root.addProperty("executable_count", executables);
        root.addProperty("argument_count", arguments);
        if (tree.has("children")) {
            root.addProperty("root_command_count", tree.getAsJsonObject("children").size());
        }
        root.add("tree", tree);
        write(context, root);
        return nodes;
    }

    private static int count(JsonObject node) {
        int total = 1;
        JsonElement children = node.get("children");
        if (children != null && children.isJsonObject()) {
            for (JsonElement child : children.getAsJsonObject().asMap().values()) {
                if (child.isJsonObject()) {
                    total += count(child.getAsJsonObject());
                }
            }
        }
        return total;
    }

    private static int countExecutable(JsonObject node) {
        int total = node.has("executable") && node.get("executable").getAsBoolean() ? 1 : 0;
        JsonElement children = node.get("children");
        if (children != null && children.isJsonObject()) {
            for (JsonElement child : children.getAsJsonObject().asMap().values()) {
                if (child.isJsonObject()) {
                    total += countExecutable(child.getAsJsonObject());
                }
            }
        }
        return total;
    }

    private static int countArguments(JsonObject node) {
        int total = "argument".equals(node.has("type") ? node.get("type").getAsString() : "") ? 1 : 0;
        JsonElement children = node.get("children");
        if (children != null && children.isJsonObject()) {
            for (JsonElement child : children.getAsJsonObject().asMap().values()) {
                if (child.isJsonObject()) {
                    total += countArguments(child.getAsJsonObject());
                }
            }
        }
        return total;
    }
}
