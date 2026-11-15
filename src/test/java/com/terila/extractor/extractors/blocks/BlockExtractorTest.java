package com.terila.extractor.extractors.blocks;

// Written by AI
import com.terila.extractor.TestSupport;
import com.terila.extractor.bootstrap.VanillaDataLoader;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorLogger;
import com.terila.extractor.extractors.blocks.model.BlockData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the block catalogue builder.
 *
 * <p>Building the catalogue is the most expensive and the most reflective step in the run: it reads
 * destroy time, explosion resistance, friction, collision and light out of the live registry. These
 * tests check the values it derives against the same methods the game itself uses, for a handful of
 * blocks whose properties are well known, plus the structural invariants the rest of the pipeline
 * depends on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockExtractorTest {

    private Map<String, BlockData> catalog;
    private BlockExtractor.Catalog built;

    @BeforeAll
    void buildCatalog() {
        TestSupport.bootStrap();
        ExtractorLogger.setVerbose(false);
        try (VanillaDataLoader data = VanillaDataLoader.load(null, ExtractorLogger.scoped("test"))) {
            ExtractorContext context = ExtractorContext.builder()
                    .config(ExtractorConfig.builder(Path.of("build", "test-output", "catalog"))
                            .threadCount(1).build())
                    .registryAccess(data.registryAccess())
                    .build();
            built = BlockExtractor.buildCatalog(BuiltInRegistries.BLOCK, context,
                    ExtractorConfig.DEFAULT_FLOAT_PRECISION);

            Map<String, BlockData> byId = new LinkedHashMap<>();
            built.blocks().forEach(block -> byId.put(block.id(), block));
            catalog = byId;
        }
    }

    @Test
    void coversEveryRegisteredBlockExactlyOnce() {
        assertEquals(BuiltInRegistries.BLOCK.size(), built.blocks().size());

        List<String> ids = built.blocks().stream().map(BlockData::id).toList();
        assertEquals(TestSupport.sorted(ids), ids, "the catalogue is not in identifier order");
        assertEquals(ids.size(), ids.stream().distinct().count(), "duplicate block ids");
    }

    /** The derived physics has to match the live registry, not a snapshot of it. */
    @Test
    void derivesPhysicsFromTheLiveRegistry() {
        Block stone = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:stone"));
        BlockData data = catalog.get("minecraft:stone");

        assertNotNull(data);
        assertEquals(stone.defaultBlockState()
                        .getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                data.hardness(), 1e-6);
        assertEquals(stone.getExplosionResistance(), data.blastResistance(), 1e-6);
        assertEquals(stone.getFriction(), data.friction(), 1e-6);
        assertEquals(stone.getJumpFactor(), data.jumpFactor(), 1e-6);
        assertEquals(stone.getSpeedFactor(), data.speedFactor(), 1e-6);
        assertEquals(stone.defaultBlockState().getLightEmission(), data.lightEmission());
        assertEquals(stone.defaultBlockState().isSolidRender(), data.solidRender());
        assertEquals(stone.defaultBlockState().canOcclude(), data.canOcclude());
        assertEquals(stone.defaultBlockState().isLightPermeable(), data.lightPermeable());
        assertFalse(data.air());
        assertFalse(data.liquid());
        assertNotNull(data.soundType(), "stone has a sound group, so it must be recorded");
        assertNotNull(data.mapColor(), "stone has a map colour");
    }

    @Test
    void identifiesAirAndLiquidBlocks() {
        assertTrue(catalog.get("minecraft:air").air(), "minecraft:air must be flagged as air");
        assertTrue(catalog.get("minecraft:water").liquid(), "water must be flagged as a liquid");
        assertFalse(catalog.get("minecraft:stone").liquid());
    }

    /**
     * Only the block registry's two liquids are liquids.
     *
     * <p>The flag used to be derived from {@code getFluidState().isEmpty()}, which also flagged every
     * block the game models as permanently underwater. Carrying a fluid and being a liquid are two
     * different facts and now have two different fields.
     */
    @Test
    void flagsOnlyLiquidBlocksAsLiquid() {
        List<String> liquids = built.blocks().stream().filter(BlockData::liquid)
                .map(BlockData::id).toList();
        assertEquals(List.of("minecraft:lava", "minecraft:water"), liquids,
                "the only liquid blocks are water and lava; flowing_* are fluid states, not blocks");

        BlockData conduit = catalog.get("minecraft:conduit");
        assertFalse(conduit.liquid(), "a conduit is not a liquid block");
        assertTrue(conduit.hasFluid(), "a conduit still carries water");
        assertEquals("minecraft:water", conduit.fluidType());

        BlockData coral = catalog.get("minecraft:brain_coral");
        assertFalse(coral.liquid(), "coral is not a liquid block");
        assertEquals("minecraft:water", coral.fluidType());

        BlockData kelp = catalog.get("minecraft:kelp_plant");
        assertFalse(kelp.liquid());
        assertEquals("minecraft:water", kelp.fluidType());

        BlockData stone = catalog.get("minecraft:stone");
        assertFalse(stone.hasFluid());
        assertNull(stone.fluidType(), "a block without a fluid must not name one");
    }

    /** The new flags have to stay mutually consistent for every block, not just the sampled ones. */
    @Test
    void fluidFlagsAreConsistentForEveryBlock() {
        for (BlockData block : built.blocks()) {
            assertEquals(block.fluidType() != null, block.hasFluid(),
                    block.id() + ": has_fluid and fluid disagree");
            if (block.waterlogged()) {
                assertTrue(block.hasFluid(), block.id() + " is waterlogged without a fluid");
            }
            if (block.liquid()) {
                assertTrue(block.hasFluid(), block.id() + " is a liquid block without a fluid state");
            }
            assertTrue(block.igniteOdds() >= 0, block.id() + " has negative ignition odds");
            assertTrue(block.burnOdds() >= 0, block.id() + " has negative burn odds");
        }
    }

    /** Replaceability is the game's own answer, and it is what grass and vines need. */
    @Test
    void recordsReplaceability() {
        assertTrue(catalog.get("minecraft:short_grass").replaceable(),
                "grass can be built over without breaking it");
        assertTrue(catalog.get("minecraft:vine").replaceable());
        assertFalse(catalog.get("minecraft:stone").replaceable());
        assertFalse(catalog.get("minecraft:grass_block").replaceable());
    }

    /** The presence of a block entity is answered by the same predicate the mapping document uses. */
    @Test
    void recordsBlockEntityPresence() {
        assertTrue(catalog.get("minecraft:chest").hasBlockEntity());
        assertTrue(catalog.get("minecraft:furnace").hasBlockEntity());
        assertFalse(catalog.get("minecraft:stone").hasBlockEntity());

        Block chest = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:chest"));
        boolean accepted = false;
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            accepted |= type.isValid(chest.defaultBlockState());
        }
        assertEquals(accepted, catalog.get("minecraft:chest").hasBlockEntity());
    }

    /** Fire odds come from the live fire block rather than from a copied table. */
    @Test
    void recordsTheGamesFireOdds() {
        BlockData planks = catalog.get("minecraft:oak_planks");
        assertTrue(planks.igniteOdds() > 0, "oak planks catch fire: " + planks.igniteOdds());
        assertTrue(planks.burnOdds() > 0, "oak planks spread fire: " + planks.burnOdds());

        BlockData stone = catalog.get("minecraft:stone");
        assertEquals(0, stone.igniteOdds(), "stone does not burn");
        assertEquals(0, stone.burnOdds());
    }

    /** Waterlogging is the fluid that comes from a property, not from the block being submerged. */
    @Test
    void recordsWaterloggedDefaultStates() {
        assertFalse(catalog.get("minecraft:oak_slab").waterlogged(),
                "a slab is dry in its default state");
        assertFalse(catalog.get("minecraft:stone").waterlogged(),
                "stone has no waterlogged property at all");

        long waterlogged = built.blocks().stream().filter(BlockData::waterlogged).count();
        assertTrue(waterlogged > 0, "some waterloggable blocks default to holding water");

        for (BlockData block : built.blocks()) {
            BlockState state = BuiltInRegistries.BLOCK
                    .getValue(Identifier.parse(block.id())).defaultBlockState();
            boolean expected = state.hasProperty(BlockStateProperties.WATERLOGGED)
                    && state.getValue(BlockStateProperties.WATERLOGGED);
            assertEquals(expected, block.waterlogged(), block.id() + ": waterlogged disagrees with the game");
        }
    }

    /** The render flags replace the deprecated {@code isSolid} and have to be self-consistent. */
    @Test
    void recordsTheRenderFlags() {
        BlockData stone = catalog.get("minecraft:stone");
        assertTrue(stone.solidRender(), "stone is drawn as a full opaque block");
        assertTrue(stone.canOcclude(), "stone hides its neighbours' faces");
        assertFalse(stone.lightPermeable(), "stone blocks light");

        BlockData glass = catalog.get("minecraft:glass");
        assertTrue(glass.lightPermeable(), "glass lets light through");

        BlockData air = catalog.get("minecraft:air");
        assertFalse(air.solidRender());
        assertTrue(air.lightPermeable());
    }

    /** Light emission is a property of the default state, so torch-shaped blocks must report it. */
    @Test
    void recordsLightEmission() {
        assertTrue(catalog.get("minecraft:glowstone").lightEmission() >= 15,
                "glowstone lights up a room");
        assertEquals(0, catalog.get("minecraft:stone").lightEmission());
    }

    /** Harvest tools come from the {@code minecraft:mineable/*} tags, not from a hard-coded table. */
    @Test
    void derivesHarvestToolsFromTheMineableTags() {
        BlockData stone = catalog.get("minecraft:stone");
        assertTrue(stone.harvestTools().contains("pickaxe"),
                "stone is mineable with a pickaxe: " + stone.harvestTools());

        BlockData oakLog = catalog.get("minecraft:oak_log");
        assertTrue(oakLog.harvestTools().contains("axe"),
                "logs are mineable with an axe: " + oakLog.harvestTools());

        BlockData diamondOre = catalog.get("minecraft:diamond_ore");
        assertTrue(diamondOre.requiresCorrectTool(), "diamond ore needs the right tool for drops");
        assertFalse(catalog.get("minecraft:dirt").requiresCorrectTool());
    }

    /**
     * The state span must be consistent with the states themselves and with the game's own ids.
     *
     * <p>This is the field engine code uses to index a block state table, so an off-by-one here would
     * be invisible in the JSON and wrong at runtime.
     */
    @Test
    void stateSpanMatchesTheRegisteredStates() {
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:oak_log"));
        BlockData data = catalog.get("minecraft:oak_log");

        assertEquals(block.getStateDefinition().getPossibleStates().size(), data.states().size());
        assertEquals(data.states().size(), data.states().stream()
                .mapToInt(state -> state.id()).distinct().count(), "duplicate state ids");
        assertEquals(Block.getId(block.defaultBlockState()), data.defaultState().id());
        assertEquals(data.states().stream().mapToInt(state -> state.id()).min().orElseThrow(),
                data.minStateId());
        assertEquals(data.states().stream().mapToInt(state -> state.id()).max().orElseThrow(),
                data.maxStateId());
    }

    /** States of one block must be contiguous in the id table. */
    @Test
    void aBlocksStateIdsAreContiguous() {
        BlockData data = catalog.get("minecraft:oak_log");

        assertEquals(data.states().size(), data.maxStateId() - data.minStateId() + 1,
                "state ids of a block are allocated as a contiguous run");
    }

    @Test
    void describesEveryPropertyWithItsValues() {
        BlockData oakLog = catalog.get("minecraft:oak_log");
        var axis = oakLog.properties().stream()
                .filter(property -> property.name().equals("axis"))
                .findFirst()
                .orElseThrow();

        assertEquals("axis", axis.name());
        assertTrue(axis.values().containsAll(List.of("x", "y", "z")),
                "axis values: " + axis.values());

        // The default state has to agree with one of the declared values.
        String recorded = oakLog.defaultState().properties().get("axis");
        assertNotNull(recorded, "the default state must record its axis");
        assertTrue(axis.values().contains(recorded), recorded + " is not a declared value");
    }

    /** Blocks without properties still have exactly one state. */
    @Test
    void propertylessBlocksHaveASingleState() {
        BlockData stone = catalog.get("minecraft:stone");

        assertTrue(stone.properties().isEmpty());
        assertEquals(1, stone.states().size());
    }

    /** Identifiers are ordered, which is what makes the output diffable between runs. */
    @Test
    void catalogIsOrderedByIdentifier() {
        List<String> first = built.blocks().stream().map(BlockData::id).toList();
        Map<String, BlockData> reordered = new TreeMap<>(catalog);

        assertEquals(List.copyOf(reordered.keySet()), first);
    }

    /** Every block has to serialise; a null name would break the schema, so it is checked here. */
    @Test
    void everyBlockHasTheSchemaRequiredFields() {
        for (BlockData block : built.blocks()) {
            assertNotNull(block.id(), "id is required");
            assertNotNull(block.name(), block.id() + " has no display name");
            assertNotNull(block.harvestTools(), block.id() + " has no harvest tool list");
            assertNotNull(block.defaultState(), block.id() + " has no default state");
            assertTrue(block.minStateId() >= 0, block.id() + " has no state ids");
        }
    }
}
