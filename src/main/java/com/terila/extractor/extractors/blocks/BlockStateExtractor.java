package com.terila.extractor.extractors.blocks;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.blocks.model.BlockData;
import com.terila.extractor.util.StringUtil;

import java.io.IOException;
import java.util.Set;

/**
 * Writes one file per block under {@code blocks/blockstates/}, each listing every state of that
 * block with its global state id.
 */
public final class BlockStateExtractor extends AbstractExtractor {

    public BlockStateExtractor() {
        super("blockstates", DataType.BLOCKS, "blocks/blockstates");
    }

    @Override
    public Set<Class<? extends DataExtractor>> dependencies() {
        return Set.of(BlockExtractor.class);
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        BlockDataCatalog catalog = BlockDataCatalog.of(context);

        int written = 0;
        for (BlockData block : catalog.blocks()) {
            JsonObject root = new JsonObject();
            root.add("_meta", context.meta(name(), block.states().size()));
            root.addProperty("block_id", block.id());
            root.addProperty("default_state_id", block.defaultState().id());
            root.addProperty("min_state_id", block.minStateId());
            root.addProperty("max_state_id", block.maxStateId());

            JsonArray states = new JsonArray();
            block.states().forEach(state -> states.add(state.toJson(false)));
            root.add("states", states);

            context.writer().write("blocks/blockstates/" + fileName(block.id()), root);
            written++;
        }
        return written;
    }

    /** {@code minecraft:oak_log} -> {@code minecraft_oak_log.json}. */
    static String fileName(String blockId) {
        return StringUtil.toFileName(blockId) + ".json";
    }
}
