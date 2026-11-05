package com.terila.extractor.extractors.items;

// Written by AI
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.items.model.ItemData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;

/**
 * Shares one item scan between every item extractor.
 *
 * <p>Serialising all 111 data component types per item is expensive, and resolving a display name
 * touches the game's (unsynchronised) component cache, so the scan happens exactly once, on the
 * thread that materialises the catalog. Every item extractor reads the result instead of walking the
 * registry again.
 */
public record ItemCatalog(
        /**
         * Every item, in identifier order; the same list every item extractor reads.
         */
        List<ItemData> items) {

    /**
     * @param context the run's context.
     * @return the run's single item catalog, built on first use.
     */
    public static ItemCatalog of(ExtractorContext context) {
        return context.shared(ItemCatalog.class, () -> {
            var registry = context.registry(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
            List<ItemData> items = new java.util.ArrayList<>();
            for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> entry
                    : com.terila.extractor.util.RegistryUtil.entries(registry)) {
                try {
                    items.add(ItemExtractor.describe(registry, entry.getKey(), entry.getValue(), context));
                } catch (RuntimeException failure) {
                    context.errors().error("ITEM_EXTRACTION_FAILED", "item_catalog",
                            entry.getKey().identifier().toString(), null, String.valueOf(failure));
                }
            }
            return new ItemCatalog(List.copyOf(items));
        });
    }

    /** Items that declare the given component. */
    public List<ItemData> withComponent(String componentId) {
        return items.stream()
                .filter(item -> item.components().containsKey(componentId))
                .toList();
    }

    /** {@code true} when the given item is present. */
    public boolean contains(String itemId) {
        return items.stream().anyMatch(item -> item.id().equals(itemId));
    }

    /** Map view for quick lookups. */
    public Map<String, ItemData> byId() {
        return items.stream().collect(java.util.stream.Collectors.toMap(
                ItemData::id, item -> item, (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
