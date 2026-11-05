package com.terila.extractor.extractors;

// Written by AI
import com.terila.extractor.core.ExtractorRegistry;
import com.terila.extractor.extractors.advancements.AdvancementExtractor;
import com.terila.extractor.extractors.assets.ClientAssetExtractor;
import com.terila.extractor.extractors.blocks.BlockExtractor;
import com.terila.extractor.extractors.blocks.BlockEntityMappingExtractor;
import com.terila.extractor.extractors.blocks.BlockLootExtractor;
import com.terila.extractor.extractors.blocks.BlockMaterialExtractor;
import com.terila.extractor.extractors.blocks.BlockPropertyExtractor;
import com.terila.extractor.extractors.blocks.BlockShapeExtractor;
import com.terila.extractor.extractors.blocks.BlockSoundExtractor;
import com.terila.extractor.extractors.blocks.BlockStateExtractor;
import com.terila.extractor.extractors.commands.CommandTreeExtractor;
import com.terila.extractor.extractors.damage.DamageTypeExtractor;
import com.terila.extractor.extractors.enchantments.EnchantmentEffectExtractor;
import com.terila.extractor.extractors.enchantments.EnchantmentExtractor;
import com.terila.extractor.extractors.entities.EntityAttributeExtractor;
import com.terila.extractor.extractors.entities.EntityExtractor;
import com.terila.extractor.extractors.entities.EntityMetadataExtractor;
import com.terila.extractor.extractors.entities.EntityPoseExtractor;
import com.terila.extractor.extractors.functions.FunctionExtractor;
import com.terila.extractor.extractors.gameplay.GameRuleExtractor;
import com.terila.extractor.extractors.gameplay.PotionExtractor;
import com.terila.extractor.extractors.item_modifiers.ItemModifierExtractor;
import com.terila.extractor.extractors.items.ItemComponentExtractor;
import com.terila.extractor.extractors.items.ItemEnchantmentExtractor;
import com.terila.extractor.extractors.items.ItemExtractor;
import com.terila.extractor.extractors.items.ItemFoodExtractor;
import com.terila.extractor.extractors.items.ItemToolExtractor;
import com.terila.extractor.extractors.loot.LootTableExtractor;
import com.terila.extractor.extractors.misc.MiscRegistryExtractors;
import com.terila.extractor.extractors.misc.SulfurCubeArchetypeExtractor;
import com.terila.extractor.extractors.packets.PacketIdExtractor;
import com.terila.extractor.extractors.packets.PacketStructureExtractor;
import com.terila.extractor.extractors.predicates.PredicateExtractor;
import com.terila.extractor.extractors.protocol.ProtocolSummaryExtractor;
import com.terila.extractor.extractors.protocol.FeatureFlagsExtractor;
import com.terila.extractor.extractors.protocol.KnownPacksExtractor;
import com.terila.extractor.extractors.recipes.RecipeExtractor;
import com.terila.extractor.extractors.registry.AllRegistriesExtractor;
import com.terila.extractor.extractors.reports.BiomeParametersExtractor;
import com.terila.extractor.extractors.reports.BlockStateIdReportExtractor;
import com.terila.extractor.extractors.reports.CommandReportExtractor;
import com.terila.extractor.extractors.reports.DataComponentDefaultExtractor;
import com.terila.extractor.extractors.reports.DatapackFormatExtractor;
import com.terila.extractor.extractors.reports.JsonRpcExtractor;
import com.terila.extractor.extractors.reports.RegistryReportExtractor;
import com.terila.extractor.extractors.structures.ProcessorListExtractor;
import com.terila.extractor.extractors.structures.StructureTemplateExtractor;
import com.terila.extractor.extractors.structures.TemplatePoolExtractor;
import com.terila.extractor.extractors.translations.TranslationExtractor;
import com.terila.extractor.extractors.tags.AllTagsExtractor;
import com.terila.extractor.extractors.tags.BlockTagExtractor;
import com.terila.extractor.extractors.tags.EffectiveFunctionTagExtractor;
import com.terila.extractor.extractors.tags.EntityTypeTagExtractor;
import com.terila.extractor.extractors.tags.FluidTagExtractor;
import com.terila.extractor.extractors.tags.FunctionTagExtractor;
import com.terila.extractor.extractors.tags.GameEventTagExtractor;
import com.terila.extractor.extractors.tags.ItemTagExtractor;
import com.terila.extractor.extractors.tags.VillagerTradeTagExtractor;
import com.terila.extractor.extractors.trims.TrimMaterialExtractor;
import com.terila.extractor.extractors.trims.TrimPatternExtractor;
import com.terila.extractor.extractors.worldgen.BiomeExtractor;
import com.terila.extractor.extractors.worldgen.FeatureExtractor;
import com.terila.extractor.extractors.worldgen.DensityFunctionExtractor;
import com.terila.extractor.extractors.worldgen.DimensionTypeExtractor;
import com.terila.extractor.extractors.worldgen.MultiNoiseExtractor;
import com.terila.extractor.extractors.worldgen.NoiseSettingsExtractor;
import com.terila.extractor.extractors.worldgen.PlacedFeatureExtractor;
import com.terila.extractor.extractors.worldgen.StructureExtractor;
import com.terila.extractor.extractors.worldgen.StructureSetExtractor;
import com.terila.extractor.core.DataExtractor;

import java.util.List;

/**
 * The canonical set of extractors.
 *
 * <p>Order here is only a baseline: {@link com.terila.extractor.core.ExtractorPipeline} reorders
 * entries to satisfy dependencies and runs independent extractors concurrently.
 *
 * <p>Three groups of extractors work together to make the output complete:
 *
 * <ol>
 *   <li>the curated extractors, which give each engine-facing data set a hand picked shape;</li>
 *   <li>{@link AllRegistriesExtractor}, {@link AllTagsExtractor} and
 *       {@link StructureTemplateExtractor}, which walk every registry, every tag binding and every
 *       NBT template so nothing can be missed by a curated extractor;</li>
 *   <li>the report-driven extractors under {@code extractors.reports}, which read what the game
 *       itself writes with {@code net.minecraft.data.Main} — packet ids, block state ids, registry
 *       protocol ids, the serialised command tree, default item components and the JSON-RPC API.</li>
 * </ol>
 */
public final class Extractors {

    private Extractors() {
    }

    public static ExtractorRegistry defaultRegistry() {
        ExtractorRegistry registry = new ExtractorRegistry();

        registry.registerAll(List.<DataExtractor>of(
                // Blocks (the catalog must come first; the rest depend on it).
                new BlockExtractor(),
                new BlockStateExtractor(),
                new BlockPropertyExtractor(),
                new BlockShapeExtractor(),
                new BlockSoundExtractor(),
                new BlockMaterialExtractor(),
                new BlockLootExtractor(),
                new BlockEntityMappingExtractor(),

                // Items.
                new ItemExtractor(),
                new ItemComponentExtractor(),
                new ItemFoodExtractor(),
                new ItemToolExtractor(),
                new ItemEnchantmentExtractor(),

                // Entities.
                new EntityExtractor(),
                new EntityMetadataExtractor(),
                new EntityPoseExtractor(),
                new EntityAttributeExtractor(),

                // Recipes.
                new RecipeExtractor(),

                // Tags.
                new BlockTagExtractor(),
                new ItemTagExtractor(),
                new EntityTypeTagExtractor(),
                new FluidTagExtractor(),
                new GameEventTagExtractor(),
                new FunctionTagExtractor(),
                new EffectiveFunctionTagExtractor(),
                new VillagerTradeTagExtractor(),

                // Completeness: every tag of every registry, including categories no curated
                // extractor covers (enchantments, damage types, potions, worldgen, ...).
                new AllTagsExtractor(),

                // Worldgen.
                new BiomeExtractor(),
                new DimensionTypeExtractor(),
                new FeatureExtractor(),
                new PlacedFeatureExtractor(),
                new StructureExtractor(),
                new StructureSetExtractor(),
                new TemplatePoolExtractor(),
                new ProcessorListExtractor(),
                new NoiseSettingsExtractor(),
                new DensityFunctionExtractor(),
                new MultiNoiseExtractor(),

                // Enchantments, damage types, trims.
                new EnchantmentExtractor(),
                new EnchantmentEffectExtractor(),
                new DamageTypeExtractor(),
                new TrimMaterialExtractor(),
                new TrimPatternExtractor(),

                // 26.2 additions.
                new SulfurCubeArchetypeExtractor(),

                // Gameplay catalogs whose built-in registry dump is identity-only.
                new GameRuleExtractor(),
                new PotionExtractor(),

                // Datapack-only content.
                new AdvancementExtractor(),
                new LootTableExtractor(),
                new PredicateExtractor(),
                new ItemModifierExtractor(),
                new FunctionExtractor(),

                // Commands, packets, translations.
                new CommandTreeExtractor(),
                new PacketIdExtractor(),
                new PacketStructureExtractor(),
                new TranslationExtractor(),

                // The data generator's own reports: the only source for protocol level tables.
                new BlockStateIdReportExtractor(),
                new RegistryReportExtractor(),
                new CommandReportExtractor(),
                new DataComponentDefaultExtractor(),
                new BiomeParametersExtractor(),
                new DatapackFormatExtractor(),
                new JsonRpcExtractor(),
                new ProtocolSummaryExtractor(),
                new FeatureFlagsExtractor(),
                new KnownPacksExtractor(),

                // Completeness: every registry, and every NBT structure template.
                new AllRegistriesExtractor(),
                new StructureTemplateExtractor(),

                // Resource pack assets from the client jar.
                new ClientAssetExtractor()
        ));

        registry.registerAll(MiscRegistryExtractors.codecDumps());
        registry.registerAll(MiscRegistryExtractors.identityDumps());

        return registry;
    }
}

