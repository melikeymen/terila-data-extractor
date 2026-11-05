package com.terila.extractor.extractors.items;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.items.model.ItemData;
import com.terila.extractor.util.TextUtil;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.DamageResistant;
import net.minecraft.world.item.enchantment.Enchantable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts every item into {@code items/minecraft.json}.
 *
 * <p>Item behaviour is almost entirely expressed through data components in modern Minecraft, so
 * this extractor serialises the whole component map (every one of the 111 component types) and
 * additionally derives the commonly used flags the design asks for: stack size, durability, food,
 * rarity, enchantability and fire resistance.
 */
public final class ItemExtractor extends AbstractExtractor {

    /**
     * The damage type tag that makes an item immune to fire.
     *
     * <p>26.2 replaced the boolean {@code fire_resistant} component with the generic
     * {@code minecraft:damage_resistant}, whose payload is a holder set — either this tag or an
     * explicit list of damage types. Both spellings are understood, because a data pack may write
     * either.
     */
    private static final String FIRE_DAMAGE_TAG = "minecraft:is_fire";

    /** Damage types that imply fire immunity when an item lists them individually. */
    private static final Set<String> FIRE_DAMAGE_TYPES = Set.of(
            FIRE_DAMAGE_TAG,
            "minecraft:in_fire",
            "minecraft:on_fire",
            "minecraft:lava",
            "minecraft:hot_floor",
            "minecraft:unattended_fire",
            "minecraft:campfire"
    );

    public ItemExtractor() {
        super("items", DataType.ITEMS, "items/minecraft.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        // The catalog is shared with the other item extractors, so the (expensive) component scan
        // runs exactly once and the names are resolved by a single thread.
        List<ItemData> items = ItemCatalog.of(context).items();

        long unbound = items.stream().filter(item -> !item.componentsBound()).count();
        if (unbound > 0) {
            context.errors().warning("COMPONENTS_NOT_BOUND", name(), null,
                    unbound + " item(s) had no bound data components; run inside a game (Fabric) for "
                            + "the full component map");
        }

        JsonArray data = new JsonArray();
        items.forEach(item -> data.add(item.toJson()));
        writeEnvelope(context, data, items.size());
        return items.size();
    }

    /** Builds the {@link ItemData} for one item; also exposes the encoded components to others. */
    public static ItemData describe(net.minecraft.core.Registry<Item> registry,
                                    ResourceKey<Item> key, Item item, ExtractorContext context) {
        Map<String, JsonElement> encoded = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        boolean componentsBound = true;
        Integer maxStackSize = null;
        Integer maxDamage = null;
        boolean food = false;
        boolean fireResistant = false;
        int enchantability = -1;
        String rarity = null;

        try {
            DataComponentMap components = item.components();
            // Components are serialised through the registry-backed ops, not plain JsonOps: many of
            // them hold registry references (a block transformer, an equipment asset, a jukebox song,
            // a damage type tag, tool rules). Bare JsonOps cannot resolve a holder, so those
            // components encode as an error — which used to be written as a silent `null`, losing the
            // payload. With the lookup attached every one of them round-trips.
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, context.lookup());
            for (TypedDataComponent<?> component : components) {
                String name = componentName(component.type());
                order.add(name);
                // Canonicalised for the same reason as the member order below: a component's own
                // payload may hold another identity-keyed map.
                var encodedValue = component.encodeValue(ops);
                JsonElement json = com.terila.extractor.util.JsonUtil.canonical(encodedValue.result().orElse(null));
                if (encodedValue.result().isEmpty()) {
                    // Never silently: a component that cannot be serialised is a gap in the document,
                    // so it is recorded and fails the run's no-errors contract instead of hiding.
                    context.errors().error("COMPONENT_ENCODE_FAILED", "items",
                            key.identifier().toString(), name,
                            String.valueOf(encodedValue.error().orElse(null)));
                }
                encoded.put(name, json);
            }
            // The game's component map keys on DataComponentType instances, and that type does not
            // override hashCode, so its iteration order is the identity hash order: it changes from
            // one JVM to the next. Sorting keeps the emitted member order reproducible.
            order.sort(Comparator.naturalOrder());

            maxStackSize = components.get(DataComponents.MAX_STACK_SIZE);
            maxDamage = components.get(DataComponents.MAX_DAMAGE);
            food = components.has(DataComponents.FOOD);
            fireResistant = isFireResistant(components.get(DataComponents.DAMAGE_RESISTANT));

            // Typed accessors: the component types are part of the public API, so no reflection is
            // involved and a Minecraft update that renames a method of the payload is a compile
            // error here rather than a silently missing field in the output.
            Enchantable enchantable = components.get(DataComponents.ENCHANTABLE);
            enchantability = enchantable == null ? -1 : enchantable.value();

            Rarity rarityComponent = components.get(DataComponents.RARITY);
            rarity = rarityComponent == null ? null : rarityComponent.getSerializedName();
        } catch (RuntimeException notBound) {
            // Components are only readable once the game has initialised them, which needs a loaded
            // datapack. Head-less they stay unbound and the derived fields stay unknown.
            componentsBound = false;
        }

        int protocolId = registry.getId(item);

        return new ItemData(
                key.identifier().toString(),
                protocolId < 0 ? -1 : protocolId,
                displayName(item),
                componentsBound ? (maxStackSize == null ? 1 : maxStackSize) : -1,
                maxDamage,
                maxDamage != null,
                food,
                fireResistant,
                rarity,
                enchantability,
                item.getDescriptionId(),
                encoded,
                List.copyOf(order),
                componentsBound
        );
    }

    /**
     * Resolves the display name.
     *
     * <p>Names live in the language files, so they can only be resolved once an item stack can be
     * constructed, which in turn needs the item's data components to be bound. That binding only
     * happens inside a running game, so a head-less run falls back to {@code null} and consumers use
     * {@code translation_key} instead.
     */
    private static String displayName(Item item) {
        try {
            return TextUtil.resolve(() -> item.getName(item.getDefaultInstance()));
        } catch (RuntimeException componentsNotBound) {
            return null;
        }
    }

    /**
     * Whether a {@code minecraft:damage_resistant} payload makes the item immune to fire.
     *
     * <p>The payload is a holder set, so it is read structurally — the referenced tag's identifier,
     * or the identifiers of the individual damage types — instead of by searching the rendered JSON.
     * That distinction matters: matching on the serialised text breaks the moment the encoding
     * changes (a list instead of a tag, a renamed field, a different key order), which is exactly the
     * kind of silent data loss this extractor has to avoid.
     *
     * @param resistant the component value, or {@code null} when the item is not damage resistant.
     */
    static boolean isFireResistant(DamageResistant resistant) {
        if (resistant == null) {
            return false;
        }
        TagKey<DamageType> tag = resistant.types().unwrapKey().orElse(null);
        if (tag != null) {
            return FIRE_DAMAGE_TYPES.contains(tag.location().toString());
        }
        for (Holder<DamageType> holder : resistant.types()) {
            if (holder.unwrapKey()
                    .map(key -> FIRE_DAMAGE_TYPES.contains(key.identifier().toString()))
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** Component identifier, e.g. {@code minecraft:max_stack_size}. */
    public static String componentName(DataComponentType<?> type) {
        var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        return id != null ? id.toString() : String.valueOf(type);
    }
}
