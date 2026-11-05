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

/** Writes {@code blocks/sounds.json}: the sound group of every block, indexed both ways. */
public final class BlockSoundExtractor extends AbstractExtractor {

    public BlockSoundExtractor() {
        super("block_sounds", DataType.BLOCKS, "blocks/sounds.json");
    }

    @Override
    public Set<Class<? extends DataExtractor>> dependencies() {
        return Set.of(BlockExtractor.class);
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        BlockDataCatalog catalog = BlockDataCatalog.of(context);

        JsonObject byBlock = new JsonObject();
        Map<String, JsonArray> bySound = new TreeMap<>();

        for (BlockData block : catalog.blocks()) {
            String sound = block.soundType();
            if (sound == null) {
                continue;
            }
            byBlock.addProperty(block.id(), sound);
            bySound.computeIfAbsent(sound, ignored -> new JsonArray()).add(block.id());
        }

        JsonObject groups = new JsonObject();
        bySound.forEach(groups::add);

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), bySound.size()));
        root.add("groups", groups);
        root.add("by_block", byBlock);
        write(context, root);
        return bySound.size();
    }
}
