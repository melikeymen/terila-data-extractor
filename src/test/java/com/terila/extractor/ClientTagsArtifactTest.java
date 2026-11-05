package com.terila.extractor;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.extractors.tags.ClientTagRegistries;
import com.terila.extractor.extractors.tags.TagWirePayload;
import com.terila.extractor.WireFormatTestSupport.RawTag;
import com.terila.extractor.WireFormatTestSupport.RawTagRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests the two Update Tags artifacts: {@code tags/all.nbt} and {@code tags/client_tags.bin}.
 *
 * <p>Both files are raw {@code update_tags} (0x0E) payloads. {@code all.nbt} is the complete sweep
 * every consumer may read; {@code client_tags.bin} is the same bytes restricted to the registries a
 * vanilla client expects, in the order the capture recorded them.
 *
 * <p>The invariant that matters is not "the client file has fewer registries" but "the client file's
 * registry set is exactly the whitelist, and every block in it is byte-identical to the same block
 * in {@code all.nbt}". A re-encoded copy could drift in tag order or entry order and still look
 * valid, so the comparison is on bytes.
 *
 * <p>{@code tests/fixtures/vanilla_update_tags.hex} is the capture the whitelist was taken from.
 * When the fixture is present the tests below also compare registry sets and tag id sets with
 * vanilla; entry counts are reported but not asserted, because a minor patch may add an entry
 * without changing which tags exist.
 */
class ClientTagsArtifactTest {

    /** Raw client-bound 0x0E payload captured from vanilla. */
    private static final Path VANILLA_CAPTURE =
            Path.of("tests", "fixtures", "vanilla_update_tags.hex");

    /** Registries in this data set that vanilla does not send, so the client file must omit them. */
    private static final Set<String> NOT_SENT_BY_VANILLA = new TreeSet<>(List.of(
            "minecraft:villager_trade",
            "minecraft:worldgen/feature",
            "minecraft:worldgen/flat_level_generator_preset",
            "minecraft:worldgen/structure",
            "minecraft:worldgen/world_preset"
    ));

    @Test
    void allTagsKeepEmptyDialogTagsAndEveryWhitelistedRegistry() throws IOException {
        Map<String, RawTagRegistry> all = byRegistry(readTags("tags/all.nbt"));
        List<String> whitelist = whitelist();

        assertEquals(whitelist.size(), new TreeSet<>(whitelist).size(),
                "the configuration contains a duplicate registry id");
        for (String registry : whitelist) {
            assertNotNull(all.get(registry), registry + " is missing from tags/all.nbt");
        }

        RawTagRegistry dialog = all.get("minecraft:dialog");
        assertNotNull(dialog, "minecraft:dialog is missing from tags/all.nbt");
        assertEquals(tagsOf(TestSupport.document("tags/all.json"), "minecraft:dialog"),
                byTag(dialog).keySet(),
                "the dialog block does not carry the tags the sweep resolved");
        for (RawTag tag : dialog.tags()) {
            assertTrue(tag.ids().isEmpty(),
                    "the vanilla dialog tags are empty, but " + tag.id() + " has entries");
        }
    }

    @Test
    void clientTagsHoldExactlyTheWhitelistInOrder() throws IOException {
        List<RawTagRegistry> client = readTags(ClientTagRegistries.OUTPUT_FILE);
        List<String> whitelist = whitelist();

        assertEquals(whitelist.size(), client.size(),
                "client_tags.bin must contain one group per whitelisted registry");
        assertEquals(whitelist, client.stream().map(RawTagRegistry::id).toList(),
                "client_tags.bin registry order must follow the configuration");

        Map<String, RawTagRegistry> all = byRegistry(readTags("tags/all.nbt"));
        for (String registry : NOT_SENT_BY_VANILLA) {
            assertFalse(client.stream().anyMatch(entry -> entry.id().equals(registry)),
                    registry + " is not part of the client payload");
            assertTrue(all.containsKey(registry),
                    registry + " must stay available in tags/all.nbt");
        }

        Set<String> notWhitelisted = new TreeSet<>(all.keySet());
        notWhitelisted.removeAll(whitelist);
        for (String registry : notWhitelisted) {
            assertFalse(client.stream().anyMatch(entry -> entry.id().equals(registry)),
                    registry + " is outside the whitelist but was written to client_tags.bin");
        }
    }

    @Test
    void clientTagBlocksAreByteIdenticalToAllTags() throws IOException {
        Path output = TestSupport.extraction().output();
        byte[] all = Files.readAllBytes(output.resolve("tags/all.nbt"));
        byte[] client = Files.readAllBytes(output.resolve(ClientTagRegistries.OUTPUT_FILE));

        Map<String, byte[]> allBlocks = TagWirePayload.blocks(all);
        Map<String, byte[]> clientBlocks = TagWirePayload.blocks(client);

        for (String registry : whitelist()) {
            assertTrue(allBlocks.containsKey(registry), registry + " is missing from all.nbt");
            assertNotNull(clientBlocks.get(registry),
                    registry + " is missing from client_tags.bin");
            assertArrayEquals(allBlocks.get(registry), clientBlocks.get(registry),
                    registry + " was re-encoded instead of copied from all.nbt");
        }
        assertEquals(whitelist(), new ArrayList<>(clientBlocks.keySet()),
                "client_tags.bin contains a registry outside the whitelist");
    }

    @Test
    void clientTagsRoundTripConsumesTheWholePayload() throws IOException {
        Path file = TestSupport.extraction().output()
                .resolve(ClientTagRegistries.OUTPUT_FILE);
        byte[] payload = Files.readAllBytes(file);

        // readTagsOrder rejects a trailing byte, so a successful parse already proves the payload
        // is consumed exactly; recomposing from the parsed blocks proves the order is exact too.
        List<String> registryIds = TagWirePayload.registryIds(payload);
        assertEquals(whitelist(), registryIds,
                "client_tags.bin registry ids are not the configured order");
        assertArrayEquals(payload,
                TagWirePayload.compose(registryIds, TagWirePayload.blocks(payload)),
                "recomposing the parsed payload must reproduce the same bytes");
    }

    @Test
    void everyRegistryInTheVanillaCaptureIsInAllTags() throws IOException {
        assumeTrue(Files.isRegularFile(VANILLA_CAPTURE),
                "missing " + VANILLA_CAPTURE + " (raw 0x0E payload captured from vanilla)");

        List<RawTagRegistry> vanilla = WireFormatTestSupport.readTagsOrder(
                WireFormatTestSupport.readHexPayload(VANILLA_CAPTURE),
                VANILLA_CAPTURE.toString());
        Map<String, RawTagRegistry> all = byRegistry(readTags("tags/all.nbt"));

        for (RawTagRegistry registry : vanilla) {
            RawTagRegistry extracted = all.get(registry.id());
            assertNotNull(extracted, registry.id() + " is missing from tags/all.nbt");
            assertEquals(byTag(registry).keySet(), byTag(extracted).keySet(),
                    registry.id() + " tag names differ from the vanilla capture");
        }
    }

    @Test
    void vanillaCaptureMatchesTheClientPayload() throws IOException {
        assumeTrue(Files.isRegularFile(VANILLA_CAPTURE),
                "missing " + VANILLA_CAPTURE + " (raw 0x0E packet captured from vanilla)");

        List<RawTagRegistry> vanilla = WireFormatTestSupport.readTagsOrder(
                WireFormatTestSupport.readHexPayload(VANILLA_CAPTURE),
                VANILLA_CAPTURE.toString());
        List<RawTagRegistry> client = readTags(ClientTagRegistries.OUTPUT_FILE);

        assertEquals(new TreeSet<>(ids(vanilla)), new TreeSet<>(ids(client)),
                "client_tags.bin registry set differs from the vanilla capture");
        assertEquals(new TreeSet<>(whitelist()), new TreeSet<>(ids(client)),
                "the configuration registry set differs from the vanilla capture");

        Map<String, RawTagRegistry> vanillaByRegistry = byRegistry(vanilla);
        Map<String, RawTagRegistry> clientByRegistry = byRegistry(client);
        for (String registry : vanillaByRegistry.keySet()) {
            Map<String, RawTag> expected = byTag(vanillaByRegistry.get(registry));
            Map<String, RawTag> actual = byTag(clientByRegistry.get(registry));
            assertEquals(expected.keySet(), actual.keySet(),
                    registry + " tag ids differ from the vanilla capture");

            for (String tag : expected.keySet()) {
                int expectedEntries = expected.get(tag).ids().size();
                int actualEntries = actual.get(tag).ids().size();
                if (expectedEntries != actualEntries) {
                    // Entry counts move with minor patches; the tag set is the contract.
                    System.out.println("WARNING: " + registry + "/" + tag + " has "
                            + actualEntries + " entries, vanilla capture has " + expectedEntries);
                }
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static List<String> whitelist() throws IOException {
        return ClientTagRegistries.load()
                .orElseThrow(() -> new IOException("no " + ClientTagRegistries.configPath()))
                .registries();
    }

    private static List<RawTagRegistry> readTags(String path) throws IOException {
        Path file = TestSupport.extraction().output().resolve(path);
        assertTrue(Files.isRegularFile(file), path + " is missing");
        return WireFormatTestSupport.readTagsOrder(file);
    }

    private static List<String> ids(List<RawTagRegistry> registries) {
        return registries.stream().map(RawTagRegistry::id).toList();
    }

    private static Map<String, RawTagRegistry> byRegistry(List<RawTagRegistry> registries) {
        Map<String, RawTagRegistry> byId = new LinkedHashMap<>();
        for (RawTagRegistry registry : registries) {
            byId.put(registry.id(), registry);
        }
        return byId;
    }

    private static Map<String, RawTag> byTag(RawTagRegistry registry) {
        Map<String, RawTag> tags = new TreeMap<>();
        for (RawTag tag : registry.tags()) {
            tags.put(tag.id(), tag);
        }
        return tags;
    }

    /** Tag ids of one registry as the JSON sweep resolved them. */
    private static Set<String> tagsOf(JsonObject document, String registryId) {
        for (JsonObject group : TestSupport.objects(document.getAsJsonArray("data"))) {
            if (!group.get("registry").getAsString().equals(registryId)) {
                continue;
            }
            Set<String> ids = new TreeSet<>();
            for (JsonObject tag : TestSupport.objects(group.getAsJsonArray("tags"))) {
                ids.add(tag.get("id").getAsString());
            }
            return ids;
        }
        return Set.of();
    }
}
