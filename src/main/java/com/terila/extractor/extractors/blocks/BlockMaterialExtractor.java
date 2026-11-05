package com.terila.extractor.extractors.blocks;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.blocks.model.BlockData;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes {@code blocks/materials.json}.
 *
 * <p>Minecraft removed the {@code Material} enum in 1.20, so "material class" is derived from the
 * map colour and the fluid/collision flags instead — the closest stable approximation.
 */
public final class BlockMaterialExtractor extends AbstractExtractor {

    public BlockMaterialExtractor() {
        super("block_materials", DataType.BLOCKS, "blocks/materials.json");
    }

    @Override
    public Set<Class<? extends DataExtractor>> dependencies() {
        return Set.of(BlockExtractor.class);
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        BlockDataCatalog catalog = BlockDataCatalog.of(context);

        Map<String, JsonArray> byColor = new TreeMap<>();
        JsonObject byBlock = new JsonObject();
        JsonArray liquids = new JsonArray();
        JsonArray airBlocks = new JsonArray();
        JsonArray solidBlocks = new JsonArray();

        for (BlockData block : catalog.blocks()) {
            if (block.mapColor() != null) {
                byBlock.addProperty(block.id(), block.mapColor());
                byColor.computeIfAbsent(block.mapColor(), ignored -> new JsonArray()).add(block.id());
            }
            if (block.liquid()) {
                liquids.add(block.id());
            }
            if (block.air()) {
                airBlocks.add(block.id());
            }
            if (block.solidRender()) {
                solidBlocks.add(block.id());
            }
        }

        JsonObject colors = new JsonObject();
        byColor.forEach(colors::add);

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), catalog.blocks().size()));
        root.add("by_map_color", colors);
        root.add("by_block", byBlock);
        root.add("liquid_blocks", liquids);
        root.add("air_blocks", airBlocks);
        root.add("solid_blocks", solidBlocks);
        write(context, root);
        return catalog.blocks().size();
    }
}
