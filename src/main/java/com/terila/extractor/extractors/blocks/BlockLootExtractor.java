package com.terila.extractor.extractors.blocks;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Writes {@code loot/blocks.json}: the loot table each block drops from.
 *
 * <p>Only the reference is recorded here; the table contents are emitted by
 * {@code LootTableExtractor}.
 */
public final class BlockLootExtractor extends AbstractExtractor {

    public BlockLootExtractor() {
        super("block_loot", DataType.LOOT, "loot/blocks.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        var registry = context.registry(Registries.BLOCK).orElse(BuiltInRegistries.BLOCK);

        JsonObject byBlock = new JsonObject();
        int entries = 0;
        for (Map.Entry<ResourceKey<Block>, Block> entry
                : com.terila.extractor.util.RegistryUtil.entries(registry)) {
            Optional<ResourceKey<LootTable>> lootTable = entry.getValue().getLootTable();
            if (lootTable.isEmpty()) {
                continue;
            }
            byBlock.addProperty(entry.getKey().identifier().toString(),
                    lootTable.get().identifier().toString());
            entries++;
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), entries));
        root.add("by_block", byBlock);
        write(context, root);
        return entries;
    }
}
