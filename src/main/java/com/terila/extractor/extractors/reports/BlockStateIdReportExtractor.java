package com.terila.extractor.extractors.reports;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ReportReader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes {@code blocks/state_ids.json}: the global protocol id of every block state.
 *
 * <p>Block states are addressed by a single global integer on the wire, and that numbering is a
 * property of the protocol, not of any runtime registry: {@code Block.getId(state)} is the
 * authoritative source, but the ids only exist once every block is registered, and the vanilla
 * ordering is a protocol contract. The data generator writes the whole table to
 * {@code reports/blocks.json} (7 MB of it), which is reshaped here into one compact, easy to parse
 * record per block:
 *
 * <pre>
 * { "id": "minecraft:oak_log",
 *   "properties": { "axis": ["x", "y", "z"] },
 *   "default_state_id": 140,
 *   "state_count": 3,
 *   "states": [ { "id": 139, "properties": { "axis": "x" } }, ... ] }
 * </pre>
 *
 * <p>The runtime ids are additionally compared against the report, so a mismatch surfaces as a
 * warning instead of silently corrupting the output.
 */
public final class BlockStateIdReportExtractor extends AbstractReportExtractor {

    public BlockStateIdReportExtractor() {
        super("block_state_ids", DataType.BLOCKS, "blocks/state_ids.json", "blocks.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        ReportReader reports = reports(context);
        if (reports == null) {
            return 0;
        }

        JsonElement parsed = reports.read("blocks.json").orElse(null);
        if (parsed == null || !parsed.isJsonObject()) {
            context.errors().warning("REPORT_EMPTY", name(), "blocks.json",
                    "reports/blocks.json is missing or not a JSON object");
            return 0;
        }

        JsonArray data = new JsonArray();
        int blockCount = 0;
        int stateCount = 0;
        int mismatches = 0;
        int protocolId = 0;

        for (Map.Entry<String, JsonElement> entry
                : new TreeMap<>(parsed.getAsJsonObject().asMap()).entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject block = entry.getValue().getAsJsonObject();
            JsonObject record = new JsonObject();
            record.addProperty("id", entry.getKey());

            if (block.has("properties")) {
                record.add("properties", block.get("properties"));
            }

            JsonArray states = new JsonArray();
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            int defaultId = -1;

            JsonElement stateList = block.get("states");
            if (stateList != null && stateList.isJsonArray()) {
                for (JsonElement stateElement : stateList.getAsJsonArray()) {
                    if (!stateElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject state = stateElement.getAsJsonObject();
                    JsonObject out = new JsonObject();
                    int id = state.has("id") ? state.get("id").getAsInt() : -1;
                    out.addProperty("id", id);
                    if (state.has("properties")) {
                        out.add("properties", state.get("properties"));
                    }
                    if (state.has("default") && state.get("default").getAsBoolean()) {
                        out.addProperty("default", true);
                        defaultId = id;
                    }
                    states.add(out);
                    min = Math.min(min, id);
                    max = Math.max(max, id);
                    stateCount++;
                    protocolId = Math.max(protocolId, id);
                }
            }

            if (defaultId < 0) {
                defaultId = min == Integer.MAX_VALUE ? -1 : min;
            }
            record.addProperty("default_state_id", defaultId);
            record.addProperty("min_state_id", min == Integer.MAX_VALUE ? -1 : min);
            record.addProperty("max_state_id", max == Integer.MIN_VALUE ? -1 : max);
            record.addProperty("state_count", states.size());
            record.add("states", states);

            mismatches += verify(entry.getKey(), defaultId, min, max);
            data.add(record);
            blockCount++;
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), blockCount));
        root.addProperty("source", reports.path("blocks.json").toString());
        root.addProperty("block_count", blockCount);
        root.addProperty("state_count", stateCount);
        root.addProperty("highest_state_id", protocolId);
        root.addProperty("runtime_id_mismatches", mismatches);
        root.add("data", data);
        write(context, root);

        if (mismatches > 0) {
            context.errors().warning("BLOCK_STATE_ID_MISMATCH", name(), null,
                    mismatches + " block(s) disagree with the runtime state ids");
        }
        return blockCount;
    }

    /**
     * Compares one report record with the live block registry.
     *
     * @return number of mismatching values (0 when the block is absent from the registry).
     */
    private static int verify(String blockId, int defaultId, int min, int max) {
        Block block = BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.tryParse(blockId));
        if (block == null) {
            return 0;
        }
        int runtimeMin = Integer.MAX_VALUE;
        int runtimeMax = Integer.MIN_VALUE;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            int id = Block.getId(state);
            runtimeMin = Math.min(runtimeMin, id);
            runtimeMax = Math.max(runtimeMax, id);
        }
        int runtimeDefault = Block.getId(block.defaultBlockState());

        int mismatches = 0;
        if (runtimeMin != min) {
            mismatches++;
        }
        if (runtimeMax != max) {
            mismatches++;
        }
        if (runtimeDefault != defaultId) {
            mismatches++;
        }
        return mismatches;
    }
}
