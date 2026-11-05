package com.terila.extractor.extractors.blocks;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.util.RegistryUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code blocks/block_entities.json}: the mapping from a block to the block entity type it
 * creates.
 *
 * <p>Chunk decoding needs this mapping before it can interpret block entity payloads. The game's
 * {@link BlockEntityType#isValid} is the authoritative predicate; this extractor asks every type
 * about every block rather than duplicating the valid-block sets.
 */
public final class BlockEntityMappingExtractor extends AbstractExtractor {

    public BlockEntityMappingExtractor() {
        super("block_entities", DataType.BLOCKS, "blocks/block_entities.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        List<Mapping> mappings = new ArrayList<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<BlockEntityType<?>>, BlockEntityType<?>>
                typeEntry : RegistryUtil.entries(BuiltInRegistries.BLOCK_ENTITY_TYPE)) {
            BlockEntityType<?> type = typeEntry.getValue();
            for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> blockEntry
                    : RegistryUtil.entries(BuiltInRegistries.BLOCK)) {
                if (type.isValid(blockEntry.getValue().defaultBlockState())) {
                    mappings.add(new Mapping(blockEntry.getKey().identifier().toString(),
                            BuiltInRegistries.BLOCK.getId(blockEntry.getValue()),
                            typeEntry.getKey().identifier().toString(),
                            BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(type)));
                }
            }
        }
        mappings.sort(Comparator.comparing(Mapping::block)
                .thenComparing(Mapping::blockEntityType));

        JsonArray data = new JsonArray();
        for (Mapping mapping : mappings) {
            JsonObject json = new JsonObject();
            json.addProperty("block", mapping.block());
            json.addProperty("block_protocol_id", mapping.blockProtocolId());
            json.addProperty("block_entity_type", mapping.blockEntityType());
            json.addProperty("block_entity_protocol_id", mapping.blockEntityProtocolId());
            data.add(json);
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.addProperty("source", "minecraft:block-entity-types");
        root.addProperty("mapping_count", data.size());
        root.addProperty("block_entity_type_count", BuiltInRegistries.BLOCK_ENTITY_TYPE.size());
        root.add("data", data);
        write(context, root);
        return data.size();
    }

    private record Mapping(String block, int blockProtocolId, String blockEntityType,
                           int blockEntityProtocolId) {
    }
}

