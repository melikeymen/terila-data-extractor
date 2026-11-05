package com.terila.extractor;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.WireFormatTestSupport.IdNbtWire;
import com.terila.extractor.WireFormatTestSupport.RegistryWire;
import com.terila.extractor.WireFormatTestSupport.TagsWire;
import com.terila.extractor.util.WireFormat;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.RegistrySynchronization.PackedRegistryEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level contract tests for the C++ server payloads.
 *
 * <p>These tests decode the game's own {@code ClientboundRegistryDataPacket} and
 * {@code ClientboundUpdateTagsPacket} codecs, not a reimplementation of their layout. The other
 * files are read as their documented counted {@code id + unnamed NBT} stream. A trailing byte is a
 * failure, so a writer that appends an envelope or a compression trailer cannot pass.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WireFormatIntegrationTest {

    private static Path output;

    @BeforeAll
    void extractEverything() {
        output = TestSupport.extraction().output();
    }

    @Test
    void registryFilesAreDirectRegistryDataPayloads() throws IOException {
        JsonObject index = TestSupport.document("registries/index.json");
        int checked = 0;

        for (JsonObject entry : TestSupport.objects(index.getAsJsonArray("index"))) {
            String id = entry.get("id").getAsString();
            Path file = output.resolve(entry.get("nbt").getAsString());
            assertTrue(Files.isRegularFile(file), "missing registry wire payload for " + id);

            RegistryWire wire = WireFormatTestSupport.readRegistry(file);
            assertEquals(id, wire.registryId(), "registry id in wire payload");
            assertEquals(entry.get("entry_count").getAsInt(), wire.entries().size(),
                    "entry count in wire payload for " + id);

            JsonObject dump = TestSupport.document(entry.get("dump").getAsString())
                    .getAsJsonObject("registries");
            List<JsonObject> jsonEntries = new ArrayList<>(
                    TestSupport.objects(dump.getAsJsonArray("entries")));
            jsonEntries.sort(Comparator.comparingInt(value -> value.has("protocol_id")
                    ? value.get("protocol_id").getAsInt() : Integer.MAX_VALUE));

            for (int i = 0; i < jsonEntries.size(); i++) {
                JsonObject json = jsonEntries.get(i);
                PackedRegistryEntry packed = wire.entries().get(i);
                assertEquals(json.get("id").getAsString(), packed.id().toString(),
                        id + " entry order must follow protocol_id");
                assertEquals(json.has("data"), packed.data().isPresent(),
                        id + "/" + json.get("id").getAsString()
                                + " has_nbt does not match its JSON payload");
                if (packed.data().isPresent()) {
                    assertNotEquals(0, packed.data().orElseThrow().getId(),
                            "empty NBT in " + id);
                }
            }
            checked++;
        }

        assertEquals(index.get("nbt_registry_count").getAsInt(), checked);
        assertEquals(155, checked, "expected every registry, including builtins");
    }

    @Test
    void tagsFileIsDirectUpdateTagsPayload() throws IOException {
        TagsWire wire = WireFormatTestSupport.readTags(output.resolve("tags/all.nbt"));
        JsonObject all = TestSupport.document("tags/all.json");

        Map<String, Integer> expectedTags = new TreeMap<>();
        Set<String> expectedRegistries = new TreeSet<>();
        int tagTotal = 0;
        for (JsonObject group : TestSupport.objects(all.getAsJsonArray("data"))) {
            String registryId = group.get("registry").getAsString();
            expectedRegistries.add(registryId);
            expectedTags.put(registryId, group.get("tag_count").getAsInt());
            tagTotal += group.get("tag_count").getAsInt();
        }

        Set<String> wireRegistries = new TreeSet<>();
        for (Map.Entry<ResourceKey<? extends net.minecraft.core.Registry<?>>,
                TagNetworkSerialization.NetworkPayload> entry : wire.tags().entrySet()) {
            wireRegistries.add(entry.getKey().identifier().toString());
        }
        assertEquals(expectedRegistries, wireRegistries,
                "wire registry set must match tags/all.json");
        assertEquals(all.get("value_count").getAsInt(), WireFormatTestSupport.tagsEntryCount(wire),
                "wire membership count must match tags/all.json");
        assertEquals(tagTotal, WireFormatTestSupport.tagsCount(wire),
                "empty tags are part of the payload, exactly as vanilla serialises them");

        List<String> registryIds = new ArrayList<>();
        for (Map.Entry<ResourceKey<? extends net.minecraft.core.Registry<?>>,
                TagNetworkSerialization.NetworkPayload> entry : wire.tags().entrySet()) {
            String registryId = entry.getKey().identifier().toString();
            registryIds.add(registryId);
            TagNetworkSerialization.NetworkPayload payload = entry.getValue();
            assertFalse(payload.isEmpty(), registryId + " must not carry an empty payload");
            assertEquals(expectedTags.get(registryId).intValue(), payload.size(),
                    registryId + " tag count must match tags/all.json");

            for (Map.Entry<net.minecraft.resources.Identifier, IntList> tag
                    : payload.tags().entrySet()) {
                List<Integer> ids = new ArrayList<>();
                for (int id : tag.getValue()) {
                    ids.add(id);
                }
                // Empty tags are real data and vanilla sends them, so only the order is checked.
                assertEquals(TestSupport.sorted(ids), ids,
                        registryId + "/" + tag.getKey() + " ids are not ascending");
            }
        }
        assertEquals(registryIds.size(), new TreeSet<>(registryIds).size(),
                "decoded registry groups are not unique");

        List<String> rawRegistryIds = new ArrayList<>();
        for (WireFormatTestSupport.RawTagRegistry registry
                : WireFormatTestSupport.readTagsOrder(output.resolve("tags/all.nbt"))) {
            rawRegistryIds.add(registry.id());
            List<String> rawTagIds = registry.tags().stream()
                    .map(WireFormatTestSupport.RawTag::id).toList();
            assertEquals(TestSupport.sorted(rawTagIds), rawTagIds,
                    registry.id() + " tags are not alphabetical on disk");
            for (WireFormatTestSupport.RawTag tag : registry.tags()) {
                assertEquals(TestSupport.sorted(tag.ids()), tag.ids(),
                        registry.id() + "/" + tag.id() + " ids are not ascending on disk");
            }
        }
        assertEquals(TestSupport.sorted(rawRegistryIds), rawRegistryIds,
                "registry groups are not alphabetical on disk");
    }

    @Test
    void recipesLootAndAdvancementsAreCountedIdNbtStreams() throws IOException {
        Map<String, String> files = Map.of(
                "recipes/minecraft.nbt", "recipes/minecraft.json",
                "loot/minecraft.nbt", "loot/minecraft.json",
                "advancements/minecraft.nbt", "advancements/minecraft.json");

        for (Map.Entry<String, String> entry : files.entrySet()) {
            IdNbtWire wire = WireFormatTestSupport.readIdNbt(output.resolve(entry.getKey()));
            JsonObject json = TestSupport.document(entry.getValue());

            assertEquals(json.getAsJsonArray("data").size(), wire.count(),
                    entry.getKey() + " count");
            List<String> wireIds = wire.entries().stream().map(WireFormatTestSupport.Entry::id)
                    .toList();
            List<String> jsonIds = TestSupport.entries(json).stream()
                    .map(value -> value.get("id").getAsString()).toList();
            assertEquals(TestSupport.sorted(jsonIds), wireIds,
                    entry.getKey() + " ids are not sorted");
            assertEquals(wireIds.size(), wireIds.stream().distinct().count(),
                    entry.getKey() + " contains duplicate ids");
            for (WireFormatTestSupport.Entry value : wire.entries()) {
                assertNotNull(value.nbt(), value.id() + " has no NBT payload");
                assertNotEquals(0, value.nbt().getId(), value.id() + " has an end tag");
            }
        }
    }

    @Test
    void commandTreeIsOneFramedNbtTag() throws IOException {
        Tag tag = WireFormatTestSupport.readFramedNbt(output.resolve("commands/tree.nbt"));
        assertTrue(tag instanceof CompoundTag, "command tree must be a compound NBT tag");
        CompoundTag tree = (CompoundTag) tag;
        assertTrue(tree.contains("children"), "command tree has no children");
        assertEquals("root", tree.getStringOr("type", ""));
    }

    @Test
    void compoundKeysAreCanonicalisedWithoutReorderingLists() throws IOException {
        CompoundTag first = new CompoundTag();
        first.putString("z", "last");
        CompoundTag firstNested = new CompoundTag();
        firstNested.putInt("b", 2);
        firstNested.putInt("a", 1);
        first.put("nested", firstNested);
        first.put("list", listOf(compound("y", "2", "x", "1"), compound("d", "4", "c", "3")));

        CompoundTag second = new CompoundTag();
        second.put("list", listOf(compound("y", "2", "x", "1"), compound("d", "4", "c", "3")));
        CompoundTag secondNested = new CompoundTag();
        secondNested.putInt("a", 1);
        secondNested.putInt("b", 2);
        second.put("nested", secondNested);
        second.putString("z", "last");

        assertArrayEquals(nbtBytes(first), nbtBytes(second),
                "NBT bytes must not depend on compound insertion order");
        CompoundTag canonical = (CompoundTag) WireFormat.canonicalNbt(first);
        ListTag canonicalList = (ListTag) canonical.get("list");
        assertEquals("1", ((CompoundTag) canonicalList.get(0)).getStringOr("x", ""),
                "list order must be preserved");
    }

    @Test
    void everyNbtFileIsRawWireDataAndNotGzip() throws IOException {
        try (Stream<Path> stream = Files.walk(output)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".nbt"))
                    .toList()) {
                byte[] bytes = Files.readAllBytes(file);
                assertTrue(bytes.length > 0, "empty NBT file: " + file);
                assertFalse(bytes.length >= 2 && Byte.toUnsignedInt(bytes[0]) == 0x1F
                                && Byte.toUnsignedInt(bytes[1]) == 0x8B,
                        "gzip header in " + file);
                assertNotEquals(0x0A, Byte.toUnsignedInt(bytes[0]),
                        "compound wrapper in " + file);
            }
        }
    }

    @Test
    void registryIdsAreFullNamespacedPaths() throws IOException {
        Set<String> ids = new TreeSet<>();
        try (Stream<Path> stream = Files.walk(output.resolve("registries/nbt"))) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".nbt"))
                    .toList()) {
                String id = WireFormatTestSupport.readRegistry(file).registryId();
                assertTrue(id.contains(":"), "registry id is not namespaced: " + id);
                ids.add(id);
            }
        }

        assertEquals(155, ids.size());
        assertTrue(ids.contains("minecraft:worldgen/biome"));
        assertTrue(ids.contains("minecraft:dimension_type"));
        assertFalse(ids.contains("minecraft:biome"),
                "registry id was shortened instead of keeping worldgen/biome");
    }

    private static CompoundTag compound(String... keyValues) {
        CompoundTag compound = new CompoundTag();
        for (int index = 0; index < keyValues.length; index += 2) {
            compound.putString(keyValues[index], keyValues[index + 1]);
        }
        return compound;
    }

    private static ListTag listOf(Tag... tags) {
        ListTag list = new ListTag();
        for (Tag tag : tags) {
            list.add(tag);
        }
        return list;
    }

    private static byte[] nbtBytes(Tag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            WireFormat.writeNbt(output, tag);
        }
        return bytes.toByteArray();
    }
}
