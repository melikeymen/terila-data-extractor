package com.terila.extractor.extractors.items;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.items.model.ComponentData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code items/components.json}: the complete data component type registry plus, for every
 * item, the component ids it declares by default.
 */
public final class ItemComponentExtractor extends AbstractExtractor {

    public ItemComponentExtractor() {
        super("item_components", DataType.ITEMS, "items/components.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        var registry = context.registry(Registries.DATA_COMPONENT_TYPE)
                .orElse(BuiltInRegistries.DATA_COMPONENT_TYPE);

        List<ComponentData> types = new ArrayList<>();
        for (Map.Entry<ResourceKey<DataComponentType<?>>, DataComponentType<?>> entry
                : com.terila.extractor.util.RegistryUtil.entries(registry)) {
            int protocolId = registry.getId(entry.getValue());
            types.add(new ComponentData(
                    entry.getKey().identifier().toString(),
                    protocolId < 0 ? -1 : protocolId,
                    entry.getValue().getClass().getSimpleName()));
        }

        JsonObject perItem = new JsonObject();
        for (var item : ItemCatalog.of(context).items()) {
            JsonArray ids = new JsonArray();
            item.componentOrder().forEach(ids::add);
            perItem.add(item.id(), ids);
        }

        JsonArray typeArray = new JsonArray();
        types.forEach(type -> typeArray.add(type.toJson()));

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), types.size()));
        root.add("types", typeArray);
        root.add("per_item", perItem);
        write(context, root);
        return types.size();
    }
}
