package com.terila.extractor.extractors.blocks;

// Written by AI
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.blocks.model.BlockData;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Thin facade over {@link BlockExtractor.Catalog}.
 *
 * <p>Block extraction is the most expensive part of the run, so every block related extractor
 * shares a single catalog instance through {@link ExtractorContext#shared}.
 */
public record BlockDataCatalog(
        /**
         * Every block, in identifier order; the same list every block extractor reads.
         */
        List<BlockData> blocks) {

    /**
     * @param context the run's context.
     * @return the run's single block catalog, built on first use.
     */
    public static BlockDataCatalog of(ExtractorContext context) {
        return context.shared(BlockDataCatalog.class, () -> {
            Registry<Block> registry = context.registry(Registries.BLOCK).orElse(BuiltInRegistries.BLOCK);
            return new BlockDataCatalog(BlockExtractor.buildCatalog(registry, context,
                    context.config().floatPrecision()).blocks());
        });
    }
}
