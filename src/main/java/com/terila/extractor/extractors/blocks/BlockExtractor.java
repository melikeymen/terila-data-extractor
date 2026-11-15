package com.terila.extractor.extractors.blocks;

// Written by AI
import com.google.gson.JsonArray;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.blocks.model.BlockData;
import com.terila.extractor.extractors.blocks.model.BlockPropertyData;
import com.terila.extractor.extractors.blocks.model.BlockStateData;
import com.terila.extractor.util.ColorUtil;
import com.terila.extractor.util.MathUtil;
import com.terila.extractor.util.TextUtil;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts the full block catalogue into {@code blocks/minecraft.json}.
 *
 * <p>This extractor is the single source of truth for per-block data: the other block extractors
 * read the catalog it publishes through {@link ExtractorContext#shared}.
 */
public final class BlockExtractor extends AbstractExtractor {

    /** Prefix of the vanilla tool tags that encode harvest requirements. */
    private static final String MINEABLE_PREFIX = "minecraft:mineable/";

    public BlockExtractor() {
        super("blocks", DataType.BLOCKS, "blocks/minecraft.json");
    }

    /**
     * Cached catalog shared with the other block extractors.
     *
     * @param blocks every block, in identifier order.
     */
    public record Catalog(List<BlockData> blocks) {
    }

    @Override
    public int extract(ExtractorContext context) throws Exception {
        Registry<Block> registry = registry(context, Registries.BLOCK).orElse(BuiltInRegistries.BLOCK);

        Catalog catalog = context.shared(Catalog.class,
                () -> buildCatalog(registry, context, context.config().floatPrecision()));
        JsonArray data = new JsonArray();
        for (BlockData block : catalog.blocks()) {
            data.add(block.toJson(true));
        }
        writeEnvelope(context, data, catalog.blocks().size());
        return catalog.blocks().size();
    }

    /**
     * Builds the catalog; deterministic ordering by identifier.
     *
     * @param registry  block registry to read.
     * @param context   the run's context; problems are recorded on it rather than thrown.
     * @param precision digits kept for the float fields, from {@link ExtractorConfig#floatPrecision()}.
     * @return the catalog, with a warning already recorded for any block that failed to describe.
     */
    public static Catalog buildCatalog(Registry<Block> registry, ExtractorContext context,
                                       int precision) {
        Map<Block, List<String>> harvestTools = harvestTools(registry);
        Set<Block> withBlockEntity = blocksWithBlockEntity(registry);

        List<Map.Entry<Identifier, Block>> entries = new ArrayList<>(registry.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey().identifier(), entry.getValue()))
                .toList());
        entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        List<BlockData> blocks = new ArrayList<>(entries.size());
        for (Map.Entry<Identifier, Block> entry : entries) {
            Block block = entry.getValue();
            try {
                blocks.add(describe(registry, entry.getKey(), block, harvestTools, withBlockEntity,
                        precision));
            } catch (RuntimeException failure) {
                context.errors().error("BLOCK_EXTRACTION_FAILED", "blocks",
                        entry.getKey().toString(), null, String.valueOf(failure));
            }
        }
        return new Catalog(List.copyOf(blocks));
    }

    /**
     * Physics of a block's default state.
     *
     * @param hardness       destroy time, rounded to the configured precision.
     * @param blastResistance explosion resistance, rounded.
     * @param friction       ground friction, rounded.
     * @param jumpFactor     jump multiplier, rounded.
     * @param speedFactor    movement multiplier, rounded.
     * @param lightEmission  emitted light level, 0-15.
     * @param sound          identifier of the break sound of the block's sound group.
     * @param mapColor       RGB hex of the default map colour.
     */
    record Physics(float hardness, float blastResistance, float friction, float jumpFactor,
                   float speedFactor, int lightEmission, String sound, String mapColor) {
    }

    /**
     * Behavioural flags of a block's default state.
     *
     * @param collidable     whether the collision shape is non-empty.
     * @param solidRender    {@code isSolidRender}, i.e. drawn as a full opaque block. The non-deprecated
     *                       replacement for {@code isSolid}, which 26.3 deprecated.
     * @param canOcclude     whether the state hides the faces of its neighbours.
     * @param lightPermeable whether light passes through the state.
     * @param air            whether the state is air.
     * @param liquid         whether the block itself <em>is</em> a liquid, i.e. a {@link LiquidBlock}.
     * @param fluid          whether the default state carries a fluid state. This is true for water
     *                       and lava, but also for every block the game models as permanently
     *                       underwater (corals, kelp, seagrass, conduit, sea pickle, bubble column).
     * @param requiresTool   whether breaking it correctly needs the right tool.
     * @param randomTicking  whether the state receives random ticks.
     */
    record Behaviour(boolean collidable, boolean solidRender, boolean canOcclude,
                     boolean lightPermeable, boolean air, boolean liquid, boolean fluid,
                     boolean requiresTool, boolean randomTicking) {
    }

    /**
     * Interaction facts that come from the block's own behaviour rather than from its physics.
     *
     * @param replaceable   {@code BlockState#canBeReplaced()}: grass, snow layers, vines and fire can be
     *                      placed through, stone cannot.
     * @param blockEntity   whether some {@link BlockEntityType} accepts the default state.
     * @param fluidType     identifier of the fluid the default state carries, {@code null} for none.
     * @param waterlogged   whether the state carries a fluid because its {@code waterlogged} property is
     *                      set, i.e. it is a waterloggable block currently holding water.
     * @param igniteOdds    the game's own ignition chance for the state, as {@code FireBlock} computes it.
     * @param burnOdds      the game's own burn (spread) chance for the state.
     */
    record Interaction(boolean replaceable, boolean blockEntity, String fluidType,
                       boolean waterlogged, int igniteOdds, int burnOdds) {
    }

    /**
     * Every state of a block plus the id range they occupy.
     *
     * @param states the states in the game's declaration order.
     * @param minId  lowest runtime state id, {@code -1} when the block has no states.
     * @param maxId  highest runtime state id, {@code -1} when the block has no states.
     */
    record StateSpan(List<BlockStateData> states, int minId, int maxId) {
    }

    /** Derives {@code block -> [tool classes]} from the {@code minecraft:mineable/*} tags. */
    private static Map<Block, List<String>> harvestTools(Registry<Block> registry) {
        Map<Block, List<String>> tools = new HashMap<>();
        registry.getTags().forEach(named -> {
            String tagId = named.key().location().toString();
            if (!tagId.startsWith(MINEABLE_PREFIX)) {
                return;
            }
            String tool = tagId.substring(MINEABLE_PREFIX.length());
            for (Holder<Block> holder : named) {
                tools.computeIfAbsent(holder.value(), ignored -> new ArrayList<>()).add(tool);
            }
        });
        tools.values().forEach(list -> list.sort(Comparator.naturalOrder()));
        return tools;
    }

    /** Assembles one block's catalogue entry from the parts below. */
    private static BlockData describe(Registry<Block> registry, Identifier id, Block block,
                                      Map<Block, List<String>> harvestTools, Set<Block> withBlockEntity,
                                      int precision) {
        BlockState defaultState = block.defaultBlockState();
        StateSpan span = describeStates(id, block);
        Physics physics = describePhysics(block, defaultState, precision);
        Behaviour behaviour = describeBehaviour(defaultState);
        Interaction interaction = describeInteraction(defaultState, withBlockEntity.contains(block));

        return new BlockData(
                id.toString(),
                Math.max(-1, registry.getId(block)),
                TextUtil.resolve(block::getName),
                block.getDescriptionId(),
                physics.hardness(),
                physics.blastResistance(),
                physics.friction(),
                physics.jumpFactor(),
                physics.speedFactor(),
                physics.lightEmission(),
                physics.sound(),
                physics.mapColor(),
                behaviour.collidable(),
                behaviour.solidRender(),
                behaviour.canOcclude(),
                behaviour.lightPermeable(),
                behaviour.air(),
                behaviour.liquid(),
                behaviour.requiresTool(),
                behaviour.randomTicking(),
                interaction.replaceable(),
                interaction.blockEntity(),
                interaction.fluidType(),
                interaction.waterlogged(),
                interaction.igniteOdds(),
                interaction.burnOdds(),
                harvestTools.getOrDefault(block, List.of()),
                span.states(),
                List.copyOf(describeProperties(block)),
                new BlockStateData(Block.getId(defaultState), id.toString(), propertyValues(defaultState)),
                span.minId(),
                span.maxId()
        );
    }

    /** Every possible state of the block, with the id range it occupies. */
    private static StateSpan describeStates(Identifier id, Block block) {
        List<BlockStateData> states = new ArrayList<>();
        int minStateId = Integer.MAX_VALUE;
        int maxStateId = Integer.MIN_VALUE;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            int stateId = Block.getId(state);
            minStateId = Math.min(minStateId, stateId);
            maxStateId = Math.max(maxStateId, stateId);
            states.add(new BlockStateData(stateId, id.toString(), propertyValues(state)));
        }
        return new StateSpan(List.copyOf(states),
                minStateId == Integer.MAX_VALUE ? -1 : minStateId,
                maxStateId == Integer.MIN_VALUE ? -1 : maxStateId);
    }

    /** Every state property of the block, in declaration order. */
    private static List<BlockPropertyData> describeProperties(Block block) {
        List<BlockPropertyData> properties = new ArrayList<>();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            properties.add(describeProperty(property));
        }
        return List.copyOf(properties);
    }

    /**
     * Physics of the block's default state.
     *
     * <p>The destroy speed is queried against an empty world, which is what makes it a property of
     * the block alone rather than of where it happens to stand.
     */
    private static Physics describePhysics(Block block, BlockState defaultState, int precision) {
        return new Physics(
                MathUtil.round(defaultState.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                        precision),
                MathUtil.round(block.getExplosionResistance(), precision),
                MathUtil.round(block.getFriction(), precision),
                MathUtil.round(block.getJumpFactor(), precision),
                MathUtil.round(block.getSpeedFactor(), precision),
                defaultState.getLightEmission(),
                soundTypeName(defaultState),
                ColorUtil.toHex(block.defaultMapColor().col)
        );
    }

    /**
     * Behavioural flags of the block's default state.
     *
     * <p>{@code isSolidRender}, {@code canOcclude} and {@code isLightPermeable} replace the deprecated
     * {@code isSolid}: it was the pre-26.3 name for "drawn as a full opaque block", and the two extra
     * flags it makes available are what a renderer or a lighting engine actually needs.
     */
    private static Behaviour describeBehaviour(BlockState defaultState) {
        return new Behaviour(
                !defaultState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(),
                defaultState.isSolidRender(),
                defaultState.canOcclude(),
                defaultState.isLightPermeable(),
                defaultState.isAir(),
                // isLiquid is a question about the block, not about the fluid inside it: a conduit
                // carries water but is not itself a liquid block. getFluidState().isEmpty() answers
                // the other question, which is recorded separately as has_fluid.
                defaultState.getBlock() instanceof LiquidBlock,
                !defaultState.getFluidState().isEmpty(),
                defaultState.requiresCorrectToolForDrops(),
                defaultState.isRandomlyTicking()
        );
    }

    /** Interaction facts the block's own code answers; see {@link Interaction}. */
    private static Interaction describeInteraction(BlockState defaultState, boolean blockEntity) {
        var fluidState = defaultState.getFluidState();
        Identifier fluid = fluidState.isEmpty()
                ? null
                : BuiltInRegistries.FLUID.getKey(fluidState.getType());
        boolean waterlogged = defaultState.hasProperty(BlockStateProperties.WATERLOGGED)
                && defaultState.getValue(BlockStateProperties.WATERLOGGED);
        return new Interaction(
                defaultState.canBeReplaced(),
                blockEntity,
                fluid == null ? null : fluid.toString(),
                waterlogged,
                FireOdds.ignite(defaultState),
                FireOdds.burn(defaultState)
        );
    }

    /**
     * Every block some block entity type accepts.
     *
     * <p>{@link BlockEntityType#isValid} is the game's own predicate, so this is exactly the set
     * {@code blocks/block_entities.json} maps, only asked once per block instead of per pair.
     */
    private static Set<Block> blocksWithBlockEntity(Registry<Block> registry) {
        Set<Block> result = new HashSet<>();
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            for (Block block : registry) {
                if (type.isValid(block.defaultBlockState())) {
                    result.add(block);
                }
            }
        }
        return result;
    }

    /**
     * The vanilla fire block's ignition and burn odds, reached through reflection.
     *
     * <p>{@code FireBlock#getIgniteOdds} and {@code #getBurnOdds} are private, and the maps behind them
     * are private instance state populated during bootstrap; asking the live block is still the only way
     * to get the values the game actually uses instead of a hand-copied table. Both accessors degrade to
     * {@code 0} if a future version renames them, which reads as "not flammable" rather than crashing.
     */
    private static final class FireOdds {

        private static final Method IGNITE = accessor("getIgniteOdds");
        private static final Method BURN = accessor("getBurnOdds");
        private static final Block FIRE = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:fire"));

        private static Method accessor(String name) {
            try {
                Method method = FireBlock.class.getDeclaredMethod(name, BlockState.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException missing) {
                return null;
            }
        }

        private static int ignite(BlockState state) {
            return invoke(IGNITE, state);
        }

        private static int burn(BlockState state) {
            return invoke(BURN, state);
        }

        private static int invoke(Method method, BlockState state) {
            if (method == null || FIRE == null) {
                return 0;
            }
            try {
                return (int) method.invoke(FIRE, state);
            } catch (ReflectiveOperationException failure) {
                return 0;
            }
        }
    }

    /**
     * Property name to value, in declaration order.
     *
     * @param state block state to describe.
     * @return each property of the state mapped to the name the game serialises for it.
     */
    public static LinkedHashMap<String, String> propertyValues(BlockState state) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            values.put(property.getName(), propertyName(property, state));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propertyName(Property<T> property, BlockState state) {
        return property.getName(state.getValue(property));
    }

    private static BlockPropertyData describeProperty(Property<?> property) {
        List<String> values = new ArrayList<>();
        for (Object value : property.getPossibleValues()) {
            values.add(nameUnchecked(property, value));
        }
        return new BlockPropertyData(property.getName(),
                property.getValueClass().getSimpleName().toLowerCase(java.util.Locale.ROOT), List.copyOf(values));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String nameUnchecked(Property<T> property, Object value) {
        return property.getName((T) value);
    }

    /** Sound group identified by the identifier of its break sound. */
    private static String soundTypeName(BlockState state) {
        var soundEvent = state.getSoundType().getBreakSound();
        Identifier key = BuiltInRegistries.SOUND_EVENT.getKey(soundEvent);
        return key != null ? key.toString() : null;
    }
}

