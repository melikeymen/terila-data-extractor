package com.terila.extractor.extractors.tags;

// Written by AI
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;

/** Writes {@code tags/blocks.json}, including the 26.2 {@code sulfur_blocks}/{@code cinnabar_blocks}. */
public final class BlockTagExtractor extends TagExtractor<Block> {

    public BlockTagExtractor() {
        super("block_tags", "tags/blocks.json", Registries.BLOCK);
    }
}
