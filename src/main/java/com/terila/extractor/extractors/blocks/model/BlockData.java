package com.terila.extractor.extractors.blocks.model;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Everything the engine needs about a single block.
 *
 * <p>Note on the design's {@code material} field: Minecraft removed the {@code Material} enum in
 * 1.20, so this model carries {@code map_color} instead and derives {@code harvest_tools} from the
 * {@code minecraft:mineable/*} block tags.
 *
 * @param id                  registry identifier, e.g. {@code minecraft:stone}.
 * @param protocolId          network id of the block.
 * @param name                display name, resolved from the language files ({@code null} when the
 *                            language table is unavailable, e.g. running without a client jar).
 * @param translationKey      description id, e.g. {@code block.minecraft.stone}. Always present, so
 *                            the engine can resolve the name in any locale itself.
 * @param hardness            {@code getDestroySpeed} of the default state.
 * @param blastResistance     explosion resistance.
 * @param friction            friction coefficient.
 * @param jumpFactor          jump velocity multiplier.
 * @param speedFactor         movement speed multiplier.
 * @param lightEmission       light level emitted by the default state.
 * @param soundType           sound group, identified by its break sound.
 * @param mapColor            map colour of the default state.
 * @param hasCollision        whether the default state collides.
 * @param solidRender         {@code isSolidRender} of the default state: whether it is drawn as a
 *                            full opaque block. This is what {@code BlockState.isSolid} used to
 *                            report before 26.3 deprecated it in favour of {@code isSolidRender}.
 * @param canOcclude          whether the default state hides the faces of neighbouring blocks.
 * @param lightPermeable      whether light passes through the default state.
 * @param air                 whether this is an air block.
 * @param liquid              whether the block itself is a liquid ({@code LiquidBlock}), i.e. water or
 *                            lava. This is not the same as carrying a fluid: corals and kelp are
 *                            permanently underwater but are not liquids.
 * @param requiresCorrectTool whether the correct tool is required for drops.
 * @param randomlyTicking     whether the block is marked as randomly ticking.
 * @param replaceable         {@code BlockState#canBeReplaced()}: whether another block may be placed
 *                            into this one without breaking it (grass, snow, vines, fire).
 * @param hasBlockEntity      whether some block entity type accepts the default state.
 * @param fluidType           identifier of the fluid the default state carries ({@code minecraft:water}),
 *                            {@code null} when it carries none. Corals and kelp are permanently
 *                            underwater, so they carry water without being liquids.
 * @param waterlogged         whether the fluid comes from a {@code waterlogged} property, i.e. the block
 *                            is waterloggable and currently holds water.
 * @param igniteOdds          the game's own ignition chance for the state.
 * @param burnOdds            the game's own burn (spread) chance for the state.
 * @param harvestTools        tool classes from {@code minecraft:mineable/*} tags.
 * @param states              every possible state.
 * @param properties          every declared property.
 * @param defaultState        the default state.
 * @param minStateId          lowest state id.
 * @param maxStateId          highest state id.
 */
public record BlockData(
        String id,
        int protocolId,
        String name,
        String translationKey,
        float hardness,
        float blastResistance,
        float friction,
        float jumpFactor,
        float speedFactor,
        int lightEmission,
        String soundType,
        String mapColor,
        boolean hasCollision,
        boolean solidRender,
        boolean canOcclude,
        boolean lightPermeable,
        boolean air,
        boolean liquid,
        boolean requiresCorrectTool,
        boolean randomlyTicking,
        boolean replaceable,
        boolean hasBlockEntity,
        String fluidType,
        boolean waterlogged,
        int igniteOdds,
        int burnOdds,
        List<String> harvestTools,
        List<BlockStateData> states,
        List<BlockPropertyData> properties,
        BlockStateData defaultState,
        int minStateId,
        int maxStateId
) {

    /**
     * Whether the default state carries a fluid at all.
     *
     * @return {@code true} when {@link #fluidType()} names a fluid; this is the {@code has_fluid} flag.
     */
    public boolean hasFluid() {
        return fluidType != null;
    }

    /**
     * @param includeStates whether the state list and its id range are included; the blockstate
     *                      documents carry them separately, so it is dropped there.
     * @return the block as one entry of {@code blocks/minecraft.json}.
     */
    public JsonObject toJson(boolean includeStates) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("protocol_id", protocolId);
        if (name != null) {
            json.addProperty("name", name);
        }
        if (translationKey != null) {
            json.addProperty("translation_key", translationKey);
        }
        json.addProperty("hardness", hardness);
        json.addProperty("blast_resistance", blastResistance);
        json.addProperty("friction", friction);
        json.addProperty("jump_factor", jumpFactor);
        json.addProperty("speed_factor", speedFactor);
        json.addProperty("light_emission", lightEmission);
        if (soundType != null) {
            json.addProperty("sound_type", soundType);
        }
        if (mapColor != null) {
            json.addProperty("map_color", mapColor);
        }
        json.addProperty("has_collision", hasCollision);
        json.addProperty("is_solid_render", solidRender);
        json.addProperty("can_occlude", canOcclude);
        json.addProperty("is_light_permeable", lightPermeable);
        json.addProperty("is_air", air);
        json.addProperty("is_liquid", liquid);
        json.addProperty("requires_correct_tool", requiresCorrectTool);
        json.addProperty("randomly_ticking", randomlyTicking);
        json.addProperty("replaceable", replaceable);
        json.addProperty("has_block_entity", hasBlockEntity);
        json.addProperty("has_fluid", fluidType != null);
        if (fluidType != null) {
            json.addProperty("fluid", fluidType);
        }
        json.addProperty("waterlogged", waterlogged);
        json.addProperty("ignite_odds", igniteOdds);
        json.addProperty("burn_odds", burnOdds);

        JsonArray tools = new JsonArray();
        harvestTools.forEach(tools::add);
        json.add("harvest_tools", tools);

        JsonArray propertyArray = new JsonArray();
        properties.forEach(property -> propertyArray.add(property.toJson()));
        json.add("properties", propertyArray);

        json.add("default_state", defaultState.toJson(false));
        json.addProperty("min_state_id", minStateId);
        json.addProperty("max_state_id", maxStateId);
        json.addProperty("state_count", states.size());

        if (includeStates) {
            JsonArray stateArray = new JsonArray();
            states.forEach(state -> stateArray.add(state.toJson(false)));
            json.add("states", stateArray);
        }

        return json;
    }
}
