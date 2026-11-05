package com.terila.extractor.extractors.blocks;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.blocks.model.BlockData;
import com.terila.extractor.extractors.blocks.model.BlockPropertyData;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes {@code blocks/properties.json}: the property definitions of every block, plus a global
 * index of the distinct properties in the game.
 */
public final class BlockPropertyExtractor extends AbstractExtractor {

    public BlockPropertyExtractor() {
        super("block_properties", DataType.BLOCKS, "blocks/properties.json");
    }

    @Override
    public Set<Class<? extends DataExtractor>> dependencies() {
        return Set.of(BlockExtractor.class);
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        BlockDataCatalog catalog = BlockDataCatalog.of(context);

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), catalog.blocks().size()));

        JsonObject perBlock = new JsonObject();
        // Global index keyed by property name so the engine can share property definitions.
        Map<String, BlockPropertyData> distinct = new TreeMap<>();

        for (BlockData block : catalog.blocks()) {
            if (block.properties().isEmpty()) {
                continue;
            }
            JsonArray properties = new JsonArray();
            for (BlockPropertyData property : block.properties()) {
                properties.add(property.toJson());
                distinct.putIfAbsent(property.name(), property);
            }
            perBlock.add(block.id(), properties);
        }

        JsonObject global = new JsonObject();
        for (Map.Entry<String, BlockPropertyData> entry : distinct.entrySet()) {
            global.add(entry.getKey(), entry.getValue().toJson());
        }

        root.add("distinct_properties", global);
        root.add("blocks", perBlock);
        write(context, root);
        return distinct.size();
    }

    /** Convenience for callers that need the property list of one block. */
    static Map<String, BlockPropertyData> propertiesOf(BlockData block) {
        Map<String, BlockPropertyData> map = new LinkedHashMap<>();
        block.properties().forEach(property -> map.put(property.name(), property));
        return map;
    }
}
