package com.terila.extractor.extractors.packets;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

/**
 * Writes {@code packets/ids.json}: the protocol id of every packet, per connection state and
 * direction.
 *
 * <p>Packet ids are not reachable through any public runtime API — the protocol tables are private
 * to the network layer. The game itself writes them to {@code reports/packets.json} when the data
 * generator runs, so that report is the authoritative source and is re-shaped here into the layout
 * the engine consumes. Point {@code --reports} at a data-generator output directory to enable it.
 */
public final class PacketIdExtractor extends AbstractExtractor {

    public PacketIdExtractor() {
        super("packets", DataType.PACKETS, "packets/ids.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Path report = locate(context);
        if (report == null) {
            context.errors().warning("PACKET_REPORT_UNAVAILABLE", name(), null,
                    "No reports/packets.json found; packet ids were skipped "
                            + "(pass --reports <data-generator output dir>)");
            return 0;
        }

        JsonElement parsed = JsonParser.parseString(Files.readString(report, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            context.errors().error("PACKET_REPORT_INVALID", name(), report.toString(), null,
                    "Expected a JSON object");
            return 0;
        }

        JsonObject states = new JsonObject();
        JsonArray flat = new JsonArray();
        int total = 0;

        for (var stateEntry : new TreeMap<>(parsed.getAsJsonObject().asMap()).entrySet()) {
            String state = stateEntry.getKey();
            if (!stateEntry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject directions = new JsonObject();

            for (var directionEntry : new TreeMap<>(stateEntry.getValue().getAsJsonObject().asMap())
                    .entrySet()) {
                String direction = directionEntry.getKey();
                if (!directionEntry.getValue().isJsonObject()) {
                    continue;
                }

                JsonArray packets = new JsonArray();
                var packetMap = new TreeMap<>(directionEntry.getValue().getAsJsonObject().asMap());
                for (var packetEntry : packetMap.entrySet()) {
                    JsonObject packet = new JsonObject();
                    packet.addProperty("name", packetEntry.getKey());
                    JsonElement value = packetEntry.getValue();
                    if (value.isJsonObject() && value.getAsJsonObject().has("protocol_id")) {
                        packet.addProperty("protocol_id", value.getAsJsonObject().get("protocol_id").getAsInt());
                    }
                    packets.add(packet);

                    JsonObject flatEntry = new JsonObject();
                    flatEntry.addProperty("state", state);
                    flatEntry.addProperty("direction", direction);
                    flatEntry.addProperty("name", packetEntry.getKey());
                    if (packet.has("protocol_id")) {
                        flatEntry.addProperty("protocol_id", packet.get("protocol_id").getAsInt());
                    }
                    flat.add(flatEntry);
                    total++;
                }

                directions.add(direction, packets);
            }

            states.add(state, directions);
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), total));
        root.addProperty("source", report.toString());
        root.add("states", states);
        root.add("packets", flat);
        write(context, root);
        return total;
    }

    /** Locates {@code packets.json}, either explicitly configured or under the reports root. */
    private static Path locate(ExtractorContext context) {
        String property = System.getProperty("terila.reports.packets");
        if (property != null) {
            Path explicit = Path.of(property);
            if (Files.isRegularFile(explicit)) {
                return explicit;
            }
        }

        Path reportsRoot = context.reportsRoot().orElse(null);
        if (reportsRoot == null) {
            return null;
        }
        for (Path candidate : List.of(reportsRoot.resolve("packets.json"),
                reportsRoot.resolve("reports").resolve("packets.json"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
