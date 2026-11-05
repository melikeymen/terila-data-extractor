package com.terila.extractor.extractors.enchantments;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Writes {@code enchantments/minecraft.json}: description, max level, weight, anvil cost, supported
 * items, exclusive sets, slots and the full effect map.
 */
public final class EnchantmentExtractor extends CodecRegistryDumpExtractor<Enchantment> {

    public EnchantmentExtractor() {
        super("enchantments", DataType.ENCHANTMENTS, "enchantments/minecraft.json",
                Registries.ENCHANTMENT);
    }
}
