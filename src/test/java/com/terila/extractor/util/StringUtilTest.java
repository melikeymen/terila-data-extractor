package com.terila.extractor.util;

// Written by AI
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for the identifier helpers every extractor builds output paths with. */
class StringUtilTest {

    @Test
    void splitsNamespacesAndPaths() {
        assertEquals("minecraft", StringUtil.namespace("minecraft:oak_log"));
        assertEquals("oak_log", StringUtil.path("minecraft:oak_log"));
        assertEquals("minecraft", StringUtil.namespace("oak_log"));
        assertEquals("worldgen/biome", StringUtil.path("minecraft:worldgen/biome"));
    }

    /** Output paths must be filesystem safe, including nested registry identifiers. */
    @Test
    void buildsFileNames() {
        assertEquals("minecraft_stone", StringUtil.toFileName("minecraft:stone"));
        assertEquals("minecraft_worldgen_biome", StringUtil.toFileName("minecraft:worldgen/biome"));
    }

    /** Every capital starts a new word, which is what the enum names being converted look like. */
    @Test
    void convertsToSnakeCase() {
        assertEquals("oak_log", StringUtil.toSnakeCase("oakLog"));
        assertEquals("crafting_shaped", StringUtil.toSnakeCase("CraftingShaped"));
        assertEquals("oak", StringUtil.toSnakeCase("oak"));
    }
}
