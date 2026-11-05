package com.terila.extractor.extractors.protocol;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorVersion;
import com.terila.extractor.core.ReportReader;
import net.minecraft.resources.RegistryDataLoader;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes {@code protocol/summary.json}: the version handshake and the size of the protocol surface.
 *
 * <p>A client stub needs to know which protocol it speaks before it can interpret anything else, and
 * it needs to know how many packet ids, registries and synchronised registries to expect. Every
 * number here is read from the game itself ({@link ExtractorVersion}) or counted out of the data
 * generator reports, so nothing is a hard coded constant that could drift from the version.
 */
public final class ProtocolSummaryExtractor extends AbstractExtractor {

    public ProtocolSummaryExtractor() {
        super("protocol_summary", DataType.PROTOCOL, "protocol/summary.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        JsonObject root = new JsonObject();

        JsonObject versions = new JsonObject();
        versions.addProperty("minecraft", ExtractorVersion.MINECRAFT_VERSION);
        versions.addProperty("protocol", ExtractorVersion.PROTOCOL_VERSION);
        versions.addProperty("world_version", ExtractorVersion.WORLD_VERSION);
        versions.addProperty("data_pack_version", ExtractorVersion.DATA_PACK_VERSION);
        versions.addProperty("data_pack_version_major", ExtractorVersion.DATA_PACK_VERSION_MAJOR);
        versions.addProperty("data_pack_version_minor", ExtractorVersion.DATA_PACK_VERSION_MINOR);
        versions.addProperty("resource_pack_version", ExtractorVersion.RESOURCE_PACK_VERSION);
        versions.addProperty("report_format_version", ExtractorVersion.REPORT_FORMAT_VERSION);
        root.add("versions", versions);

        // Registries the server synchronises to a joining client: anything a client must be able to
        // decode before it can render or simulate anything.
        JsonArray synchronizedIds = new JsonArray();
        for (var data : RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
            synchronizedIds.add(data.key().identifier().toString());
        }
        root.addProperty("synchronized_registry_count", synchronizedIds.size());
        root.add("synchronized_registries", synchronizedIds);

        ReportReader reports = context.reports().orElse(null);
        // The packet table is written even without the reports: the document always has the same
        // shape, and `packet_ids_available` says whether the count is real or just unpopulated.
        int packets = addPacketSummary(root, reports);
        if (reports != null) {
            addRegistrySummary(root, reports);
        }

        root.add("_meta", context.meta(name(), synchronizedIds.size() + packets));
        root.addProperty("reports_available", reports != null);
        write(context, root);
        return synchronizedIds.size() + packets;
    }

    /**
     * Counts the packet table per state and direction.
     *
     * <p>{@code packets.json} is the only source of packet ids — no runtime API exposes them — so when
     * the data generator report is missing the count is unknown rather than zero. Writing a bare
     * {@code 0} would look like a real answer, so {@code packet_ids_available} records which it is.
     */
    private static int addPacketSummary(JsonObject root, ReportReader reports) {
        JsonElement parsed = reports == null ? null : reports.read("packets.json").orElse(null);
        if (parsed == null || !parsed.isJsonObject()) {
            root.addProperty("packet_count", 0);
            root.addProperty("packet_ids_available", false);
            return 0;
        }

        JsonObject states = parsed.getAsJsonObject();
        JsonObject counts = new JsonObject();
        int total = 0;

        for (Map.Entry<String, JsonElement> state : new TreeMap<>(states.asMap()).entrySet()) {
            if (!state.getValue().isJsonObject()) {
                continue;
            }
            JsonObject directions = new JsonObject();
            for (Map.Entry<String, JsonElement> direction
                    : new TreeMap<>(state.getValue().getAsJsonObject().asMap()).entrySet()) {
                if (!direction.getValue().isJsonObject()) {
                    continue;
                }
                int count = direction.getValue().getAsJsonObject().size();
                directions.addProperty(direction.getKey(), count);
                total += count;
            }
            counts.add(state.getKey(), directions);
        }

        root.add("packet_counts", counts);
        root.addProperty("packet_states", counts.size());
        root.addProperty("packet_count", total);
        root.addProperty("packet_ids_available", true);
        return total;
    }

    /** Adds the registry totals the reports advertise. */
    private static void addRegistrySummary(JsonObject root, ReportReader reports) {
        JsonElement registries = reports.read("registries.json").orElse(null);
        if (registries != null && registries.isJsonObject()) {
            int entries = 0;
            for (JsonElement registry : registries.getAsJsonObject().asMap().values()) {
                if (!registry.isJsonObject()) {
                    continue;
                }
                JsonElement raw = registry.getAsJsonObject().get("entries");
                if (raw != null && raw.isJsonObject()) {
                    entries += raw.getAsJsonObject().size();
                }
            }
            root.addProperty("reported_registry_count", registries.getAsJsonObject().size());
            root.addProperty("reported_registry_entries", entries);
        }

        JsonElement datapack = reports.read("datapack.json").orElse(null);
        if (datapack != null && datapack.isJsonObject()) {
            JsonElement raw = datapack.getAsJsonObject().get("registries");
            if (raw != null && raw.isJsonObject()) {
                root.addProperty("datapack_registry_count", raw.getAsJsonObject().size());
            }
        }
    }
}
