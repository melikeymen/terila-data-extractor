package com.terila.extractor.extractors.enchantments;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.IdentityRegistryDumpExtractor;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;

/**
 * Writes {@code enchantments/effects.json}: the enchantment effect component types, which enumerate
 * every kind of effect an enchantment can carry.
 */
public final class EnchantmentEffectExtractor extends IdentityRegistryDumpExtractor<DataComponentType<?>> {

    public EnchantmentEffectExtractor() {
        super("enchantment_effects", DataType.ENCHANTMENTS, "enchantments/effects.json",
                Registries.ENCHANTMENT_EFFECT_COMPONENT_TYPE);
    }
}
