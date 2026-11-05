package com.terila.extractor.extractors.registry;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.terila.extractor.TestSupport;
import com.terila.extractor.bootstrap.VanillaDataLoader;
import com.terila.extractor.core.ExtractorLogger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the codec contract the registry dumps depend on.
 *
 * <p>Minecraft 26.3 turned {@code Registry.byNameCodec()} into a name codec: it encodes an entry as
 * just its identifier. Every registry document silently collapsed to a list of names until the dumps
 * were switched to the element codec the game loads data pack files with. These tests exist so that
 * cannot regress unnoticed.
 */
class RegistryCodecsTest {

    @BeforeAll
    static void boot() {
        TestSupport.bootStrap();
    }

    @Test
    void knowsEveryDataPackRegistry() {
        Set<String> ids = RegistryCodecs.datapackRegistryIds();

        assertTrue(ids.size() >= 55, "expected the full data pack surface, found " + ids.size());
        assertTrue(ids.contains("minecraft:worldgen/biome"));
        assertTrue(ids.contains("minecraft:enchantment"));
        assertTrue(ids.contains("minecraft:recipe"));
        assertTrue(ids.contains("minecraft:loot_table"));
        assertTrue(ids.contains("minecraft:advancement"));
        assertTrue(ids.contains("minecraft:predicate"));
        assertTrue(ids.contains("minecraft:item_modifier"));
        assertTrue(ids.contains("minecraft:dimension"));
    }

    /** Built-in registries are not loaded from files, so they carry no element codec. */
    @Test
    void builtInRegistriesHaveNoElementCodec() {
        assertNotNull(BuiltInRegistries.BLOCK);
        assertEquals(null, RegistryCodecs.elementCodec("minecraft:block"));
        assertNotNull(RegistryCodecs.elementCodec("minecraft:worldgen/biome"));
    }

    /**
     * The element codec carries the payload, while {@code byNameCodec()} only carries the name.
     *
     * <p>Loaded against a real data set because the codec table is built from the registries the game
     * declares at startup.
     */
    @Test
    void elementCodecCarriesThePayloadAndNameCodecDoesNot() {
        ExtractorLogger.setVerbose(false);
        try (VanillaDataLoader data = VanillaDataLoader.load(null, ExtractorLogger.scoped("test"))) {
            @SuppressWarnings("unchecked")
            Registry<Object> biomes = (Registry<Object>) (Registry<?>) data.registryAccess()
                    .lookup(ResourceKey.createRegistryKey(Identifier.parse("minecraft:worldgen/biome")))
                    .orElseThrow();

            Object plains = biomes.getValue(Identifier.parse("minecraft:plains"));
            assertNotNull(plains, "minecraft:plains must be loaded");

            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, data.registryAccess());
            Codec<Object> elementCodec = RegistryCodecs.elementCodec(biomes);
            assertNotNull(elementCodec);

            JsonElement payload = elementCodec.encodeStart(ops, plains).result().orElseThrow();
            JsonElement name = biomes.byNameCodec().encodeStart(ops, plains).result().orElseThrow();

            assertTrue(payload.isJsonObject(), "the element codec must produce the biome document");
            JsonObject biome = payload.getAsJsonObject();
            assertTrue(biome.has("temperature"), "biome payload is missing its fields: " + biome.keySet());
            assertTrue(biome.has("attributes"));

            assertTrue(name.isJsonPrimitive(), "byNameCodec now emits names only: " + name);
        }
    }
}
