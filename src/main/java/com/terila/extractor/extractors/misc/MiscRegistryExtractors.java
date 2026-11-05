package com.terila.extractor.extractors.misc;

// Written by AI
import com.terila.extractor.core.DataExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import com.terila.extractor.extractors.registry.IdentityRegistryDumpExtractor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the registry-dump extractors for every remaining registry.
 *
 * <p>The design lists one class per "misc" registry. They all share identical behaviour — dump the
 * registry, either through its codec (datapack registries with real content) or by identity
 * (id + protocol id) — so they are declared here as a table instead of ~35 near-identical classes.
 * Each entry below still produces its own output file with the documented path and name.
 */
public final class MiscRegistryExtractors {

    private MiscRegistryExtractors() {
    }

    /** Codec-serialised registries: their entries carry real payload data. */
    public static List<DataExtractor> codecDumps() {
        List<DataExtractor> extractors = new ArrayList<>();
        extractors.add(codec("jukebox_songs", DataType.MISC, "misc/jukebox_songs.json",
                Registries.JUKEBOX_SONG));
        extractors.add(codec("instruments", DataType.MISC, "misc/instruments.json",
                Registries.INSTRUMENT));
        extractors.add(codec("paintings", DataType.MISC, "misc/paintings.json",
                Registries.PAINTING_VARIANT));
        extractors.add(codec("banner_patterns", DataType.MISC, "misc/banner_patterns.json",
                Registries.BANNER_PATTERN));
        extractors.add(codec("chat_types", DataType.MISC, "misc/chat_types.json",
                Registries.CHAT_TYPE));
        extractors.add(codec("wolf_variants", DataType.MISC, "misc/wolf_variants.json",
                Registries.WOLF_VARIANT));
        extractors.add(codec("cat_variants", DataType.MISC, "misc/cat_variants.json",
                Registries.CAT_VARIANT));
        extractors.add(codec("frog_variants", DataType.MISC, "misc/frog_variants.json",
                Registries.FROG_VARIANT));
        extractors.add(codec("pig_variants", DataType.MISC, "misc/pig_variants.json",
                Registries.PIG_VARIANT));
        extractors.add(codec("cow_variants", DataType.MISC, "misc/cow_variants.json",
                Registries.COW_VARIANT));
        extractors.add(codec("chicken_variants", DataType.MISC, "misc/chicken_variants.json",
                Registries.CHICKEN_VARIANT));
        extractors.add(codec("zombie_nautilus_variants", DataType.MISC,
                "misc/zombie_nautilus_variants.json", Registries.ZOMBIE_NAUTILUS_VARIANT));
        extractors.add(codec("timelines", DataType.MISC, "misc/timelines.json", Registries.TIMELINE));
        extractors.add(codec("world_clocks", DataType.MISC, "misc/world_clocks.json",
                Registries.WORLD_CLOCK));
        extractors.add(codec("test_environments", DataType.MISC, "misc/test_environments.json",
                Registries.TEST_ENVIRONMENT));
        extractors.add(codec("test_instances", DataType.MISC, "misc/test_instances.json",
                Registries.TEST_INSTANCE));
        extractors.add(codec("dialogs", DataType.MISC, "misc/dialogs.json", Registries.DIALOG));
        return List.copyOf(extractors);
    }

    /** Identity-only registries: they matter by name and network id. */
    public static List<DataExtractor> identityDumps() {
        List<DataExtractor> extractors = new ArrayList<>();
        extractors.add(identity("sounds", "misc/sounds.json", Registries.SOUND_EVENT));
        extractors.add(identity("particles", "misc/particles.json", Registries.PARTICLE_TYPE));
        extractors.add(identity("mob_effects", "misc/mob_effects.json", Registries.MOB_EFFECT));
        extractors.add(identity("fluids", "misc/fluids.json", Registries.FLUID));
        extractors.add(identity("game_events", "misc/game_events.json", Registries.GAME_EVENT));
        extractors.add(identity("menus", "misc/menus.json", Registries.MENU));
        extractors.add(identity("attributes", "misc/attributes.json", Registries.ATTRIBUTE));
        extractors.add(identity("custom_stats", "misc/custom_stats.json", Registries.CUSTOM_STAT));
        extractors.add(identity("block_entity_types", "misc/block_entity_types.json",
                Registries.BLOCK_ENTITY_TYPE));
        extractors.add(identity("stat_types", "misc/stat_types.json", Registries.STAT_TYPE));
        extractors.add(identity("position_source_types", "misc/position_source_types.json",
                Registries.POSITION_SOURCE_TYPE));
        extractors.add(identity("command_argument_types", "misc/command_argument_types.json",
                Registries.COMMAND_ARGUMENT_TYPE));
        extractors.add(identity("data_component_predicate_types",
                "misc/data_component_predicate_types.json",
                Registries.DATA_COMPONENT_PREDICATE_TYPE));
        extractors.add(identity("data_component_types", "misc/data_component_types.json",
                Registries.DATA_COMPONENT_TYPE));

        // Type registries: they enumerate the serialisable variants of a concept and are needed to
        // interpret the datapack JSON.
        extractors.add(identity("dialog_types", "misc/dialog_types.json", Registries.DIALOG_TYPE));
        extractors.add(identity("dialog_action_types", "misc/dialog_action_types.json",
                Registries.DIALOG_ACTION_TYPE));
        extractors.add(identity("dialog_body_types", "misc/dialog_body_types.json",
                Registries.DIALOG_BODY_TYPE));
        extractors.add(identity("recipe_book_categories", "misc/recipe_book_categories.json",
                Registries.RECIPE_BOOK_CATEGORY));
        extractors.add(identity("slot_display_types", "misc/slot_display_types.json",
                Registries.SLOT_DISPLAY));
        extractors.add(identity("recipe_types", "misc/recipe_types.json", Registries.RECIPE_TYPE));
        extractors.add(identity("recipe_serializers", "misc/recipe_serializers.json",
                Registries.RECIPE_SERIALIZER));
        extractors.add(identity("loot_condition_types", "misc/loot_condition_types.json",
                Registries.LOOT_CONDITION_TYPE));
        extractors.add(identity("loot_function_types", "misc/loot_function_types.json",
                Registries.LOOT_FUNCTION_TYPE));
        extractors.add(identity("loot_pool_entry_types", "misc/loot_pool_entry_types.json",
                Registries.LOOT_POOL_ENTRY_TYPE));
        extractors.add(identity("entity_sub_predicate_types", "misc/entity_sub_predicate_types.json",
                Registries.ENTITY_SUB_PREDICATE_TYPE));
        extractors.add(identity("block_predicate_types", "misc/block_predicate_types.json",
                Registries.BLOCK_PREDICATE_TYPE));
        extractors.add(identity("pos_rule_tests", "misc/pos_rule_tests.json", Registries.POS_RULE_TEST));
        extractors.add(identity("rule_tests", "misc/rule_tests.json", Registries.RULE_TEST));
        // 26.3 folded ConfiguredFeature into Feature, so FEATURE is the configured feature registry
        // (dumped as worldgen/features). The type vocabulary is FEATURE_TYPE.
        extractors.add(identity("feature_types", "misc/feature_types.json", Registries.FEATURE_TYPE));
        // Configured carvers are a data pack registry; the type vocabulary is CARVER_TYPE.
        extractors.add(identity("carvers", "misc/carvers.json", Registries.CARVER));
        extractors.add(identity("carver_types", "misc/carver_types.json", Registries.CARVER_TYPE));
        extractors.add(identity("placement_modifiers", "misc/placement_modifiers.json",
                Registries.PLACEMENT_MODIFIER_TYPE));
        extractors.add(identity("structure_types", "misc/structure_types.json",
                Registries.STRUCTURE_TYPE));
        extractors.add(identity("enchantment_value_effect_types", "misc/enchantment_value_effect_types.json",
                Registries.ENCHANTMENT_VALUE_EFFECT_TYPE));
        return List.copyOf(extractors);
    }

    private static <T> DataExtractor codec(String name, DataType type, String path,
                                           ResourceKey<Registry<T>> key) {
        return new CodecRegistryDumpExtractor<>(name, type, path, key);
    }

    private static <T> DataExtractor identity(String name, String path, ResourceKey<Registry<T>> key) {
        return new IdentityRegistryDumpExtractor<>(name, DataType.MISC, path, key);
    }
}

