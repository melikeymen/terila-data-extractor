package com.terila.extractor.core;    /**
     * The data categories the extractor can emit. Used by {@link ExtractorConfig} to select a subset
     * of work and by {@link com.terila.extractor.core.ExtractorRegistry} for grouping.
     *
 * <p>Every category is populated from real vanilla data. The only directory vanilla ships without
 * content is {@code data/minecraft/item_modifier}: the game defines the vocabulary but no entries.
 * {@code data/minecraft/function} is also empty, but it is a command-source directory rather than a
 * registry, so its extractor documents that a datapack may add {@code .mcfunction} files. Both
 * documents say why they are empty, so a consumer can tell the difference between "no data" and
 * "data not extracted".
     */
public enum DataType {
    BLOCKS,
    ITEMS,
    ENTITIES,
    RECIPES,
    TAGS,
    WORLDGEN,
    ENCHANTMENTS,
    DAMAGE_TYPES,
    TRIMS,
    PACKETS,
    TRANSLATIONS,
    COMMANDS,
    ADVANCEMENTS,
    LOOT,
    PREDICATES,
    ITEM_MODIFIERS,
    /** {@code .mcfunction} command sources; vanilla ships none, a datapack may add them. */
    FUNCTIONS,
    /** New in Minecraft 26.2: the {@code minecraft:sulfur_cube_archetype} registry. */
    SULFUR_CUBE_ARCHETYPES,
    /** Packet ids, protocol and data pack versions, and the JSON-RPC method table. */
    PROTOCOL,
    /** Structure templates (NBT), template pools and processor lists. */
    STRUCTURES,
    /** Resource-pack assets read from the client jar (models, blockstates, atlases, fonts). */
    ASSETS,
    MISC
}

