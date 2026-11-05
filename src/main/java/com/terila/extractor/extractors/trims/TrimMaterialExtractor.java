package com.terila.extractor.extractors.trims;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.equipment.trim.TrimMaterial;

/** Writes {@code trims/materials.json}: asset name, description, ingredient and model index. */
public final class TrimMaterialExtractor extends CodecRegistryDumpExtractor<TrimMaterial> {

    public TrimMaterialExtractor() {
        super("trim_materials", DataType.TRIMS, "trims/materials.json", Registries.TRIM_MATERIAL);
    }
}
