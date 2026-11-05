package com.terila.extractor.extractors.tags;

// Written by AI
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.trading.VillagerTrade;

/** Writes {@code tags/villager_trades.json}. */
public final class VillagerTradeTagExtractor extends TagExtractor<VillagerTrade> {

    public VillagerTradeTagExtractor() {
        super("villager_trade_tags", "tags/villager_trades.json", Registries.VILLAGER_TRADE);
    }
}
