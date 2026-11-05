package com.terila.extractor.extractors.damage;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageType;

/** Writes {@code damage_types/minecraft.json}: message id, scaling, exhaustion and effects. */
public final class DamageTypeExtractor extends CodecRegistryDumpExtractor<DamageType> {

    public DamageTypeExtractor() {
        super("damage_types", DataType.DAMAGE_TYPES, "damage_types/minecraft.json",
                Registries.DAMAGE_TYPE);
    }
}
