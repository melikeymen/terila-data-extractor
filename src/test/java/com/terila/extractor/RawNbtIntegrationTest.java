package com.terila.extractor;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.WireFormatTestSupport.RegistryWire;
import com.terila.extractor.WireFormatTestSupport.TagsWire;
import com.terila.extractor.bootstrap.VanillaDataLoader;
import com.terila.extractor.core.ExtractionReport;
import com.terila.extractor.core.ExtractionRunner;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorLogger;
import com.terila.extractor.core.ExtractorRegistry;
import com.terila.extractor.core.FileManager;
import com.terila.extractor.core.NbtFormat;
import com.terila.extractor.core.RawNbtWriter;
import com.terila.extractor.extractors.advancements.AdvancementExtractor;
import com.terila.extractor.extractors.commands.CommandTreeExtractor;
import com.terila.extractor.extractors.loot.LootTableExtractor;
import com.terila.extractor.extractors.recipes.RecipeExtractor;
import com.terila.extractor.extractors.registry.AllRegistriesExtractor;
import com.terila.extractor.extractors.tags.AllTagsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the raw NBT mode end to end.
 *
 * <p>The fixture runs {@link NbtFormat#BOTH} for the six NBT-producing extractors, so the raw tree
 * and its wire counterpart are produced from the same in-memory data. The registry checks compare
 * every manifest file with the decoded registry payload; the other checks decode each standalone
 * file directly with the game's own NBT reader.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RawNbtIntegrationTest {

    private static Path output;
    private static Path rawOnlyOutput;

    @BeforeAll
    static void extractRawAndWire() throws Exception {
        TestSupport.bootStrap();
        output = Path.of("build", "test-output", "raw-nbt");
        rawOnlyOutput = Path.of("build", "test-output", "raw-nbt-only");
        FileManager.deleteRecursively(output);
        FileManager.deleteRecursively(rawOnlyOutput);

        ExtractorRegistry registry = new ExtractorRegistry().registerAll(List.of(
                new AllRegistriesExtractor(),
                new AllTagsExtractor(),
                new RecipeExtractor(),
                new LootTableExtractor(),
                new AdvancementExtractor(),
                new CommandTreeExtractor()));

        try (VanillaDataLoader data = VanillaDataLoader.load(null,
                ExtractorLogger.scoped("raw-nbt-test"))) {
            ExtractorContext.Builder context = ExtractorContext.builder()
                    .registryAccess(data.registryAccess())
                    .reloadableRegistries(data.reloadableRegistries())
                    .recipeManager(data.recipeManager())
                    .commands(data.commands())
                    .pack(data.pack())
                    .resources(data.resources());

            ExtractorConfig config = ExtractorConfig.builder(output)
                    .threadCount(2)
                    .nbtFormat(NbtFormat.BOTH)
                    .validate(false)
                    .build();
            ExtractionReport report = ExtractionRunner.run(config, registry, context);
            assertTrue(report.isFullySuccessful(), "raw fixture extractors failed");

            ExtractorConfig rawOnly = ExtractorConfig.builder(rawOnlyOutput)
                    .threadCount(2)
                    .nbtFormat(NbtFormat.RAW)
                    .validate(false)
                    .build();
            ExtractionReport rawReport = ExtractionRunner.run(rawOnly, registry, context);
            assertTrue(rawReport.isFullySuccessful(), "raw-only fixture extractors failed");
        }
    }

    @Test
    void rawOnlyModeWritesNoWirePayloads() throws IOException {
        assertFalse(containsNbtFile(rawOnlyOutput.resolve("registries/nbt")));
        assertFalse(Files.exists(rawOnlyOutput.resolve("tags/all.nbt")));
        assertFalse(Files.exists(rawOnlyOutput.resolve("tags/client_tags.bin")));
        assertFalse(Files.exists(rawOnlyOutput.resolve("recipes/minecraft.nbt")));
        assertFalse(Files.exists(rawOnlyOutput.resolve("loot/minecraft.nbt")));
        assertFalse(Files.exists(rawOnlyOutput.resolve("advancements/minecraft.nbt")));
        assertFalse(Files.exists(rawOnlyOutput.resolve("commands/tree.nbt")));
        assertTrue(Files.exists(rawOnlyOutput.resolve(
                "registries/raw/minecraft/worldgen/biome/manifest.json")));
    }

    private static boolean containsNbtFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.anyMatch(file -> file.getFileName().toString().endsWith(".nbt"));
        }
    }

    @Test
    void everyRegistryManifestIsCompleteAndMatchesHasNbt() throws IOException {
        JsonObject index = TestSupport.object(output.resolve("registries/index.json"));
        assertEquals(155, index.get("raw_registry_count").getAsInt());

        for (JsonObject entry : TestSupport.objects(index.getAsJsonArray("index"))) {
            String registryId = entry.get("id").getAsString();
            Path manifestPath = output.resolve(entry.get("raw").getAsString());
            JsonObject manifest = TestSupport.object(manifestPath);
            JsonObject dump = TestSupport.object(output.resolve(entry.get("dump").getAsString()))
                    .getAsJsonObject("registries");

            assertEquals(entry.get("entry_count").getAsInt(), manifest.size(),
                    "raw manifest entry count for " + registryId);
            Map<String, Integer> protocolIds = new HashMap<>();
            for (JsonObject dumped : TestSupport.objects(dump.getAsJsonArray("entries"))) {
                if (dumped.has("protocol_id")) {
                    protocolIds.put(dumped.get("id").getAsString(),
                            dumped.get("protocol_id").getAsInt());
                }
            }

            RegistryWire wire = WireFormatTestSupport.readRegistry(
                    output.resolve(entry.get("nbt").getAsString()));
            Map<String, Boolean> hasNbt = new HashMap<>();
            for (var packed : wire.entries()) {
                hasNbt.put(packed.id().toString(), packed.data().isPresent());
            }

            Set<String> expectedFiles = new HashSet<>();
            for (Map.Entry<String, JsonElement> manifestEntry : manifest.entrySet()) {
                String id = manifestEntry.getKey();
                JsonObject value = manifestEntry.getValue().getAsJsonObject();
                assertTrue(value.has("protocol_id"), id + " has no protocol_id");
                assertEquals(protocolIds.get(id), value.get("protocol_id").getAsInt(),
                        "protocol_id mismatch for " + id);
                assertTrue(hasNbt.containsKey(id), id + " is missing from the wire payload");

                JsonElement fileElement = value.get("file");
                boolean hasFile = fileElement != null && !fileElement.isJsonNull();
                assertEquals(hasNbt.get(id), hasFile,
                        "has_nbt/file mismatch for " + id);
                if (hasFile) {
                    String file = fileElement.getAsString();
                    expectedFiles.add(file);
                    Tag tag = readRawNbt(manifestPath.getParent().resolve(file));
                    assertNotNull(tag, "raw NBT is null for " + id);
                }
            }

            List<String> actualFiles = new ArrayList<>();
            try (Stream<Path> files = Files.list(manifestPath.getParent())) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".nbt"))
                        .toList()) {
                    actualFiles.add(file.getFileName().toString());
                }
            }
            assertEquals(new HashSet<>(actualFiles), expectedFiles,
                    "raw registry directory has missing or orphan NBT files: " + registryId);
        }
    }

    @Test
    void rawTagFilesAreValidAndMatchTheWireMembership() throws IOException {
        JsonObject all = TestSupport.object(output.resolve("tags/all.json"));
        JsonObject manifest = TestSupport.object(output.resolve("tags/raw/manifest.json"));
        TagsWire wire = WireFormatTestSupport.readTags(output.resolve("tags/all.nbt"));

        Map<String, TagNetworkSerialization.NetworkPayload> payloads = new TreeMap<>();
        for (Map.Entry<ResourceKey<? extends net.minecraft.core.Registry<?>>,
                TagNetworkSerialization.NetworkPayload> entry : wire.tags().entrySet()) {
            payloads.put(entry.getKey().identifier().toString(), entry.getValue());
        }

        int expectedTags = 0;
        for (JsonObject group : TestSupport.objects(all.getAsJsonArray("data"))) {
            String registryId = group.get("registry").getAsString();
            TagNetworkSerialization.NetworkPayload payload = payloads.get(registryId);
            for (JsonObject tag : TestSupport.objects(group.getAsJsonArray("tags"))) {
                String tagId = tag.get("id").getAsString();
                String key = registryId + "|" + tagId;
                JsonObject rawEntry = manifest.getAsJsonObject(key);
                assertNotNull(rawEntry, "missing raw tag manifest entry " + key);
                assertEquals(registryId, rawEntry.get("registry").getAsString());
                assertEquals(tagId, rawEntry.get("tag").getAsString());
                assertEquals(tag.get("value_count").getAsInt(),
                        rawEntry.get("entry_count").getAsInt());

                Path file = output.resolve("tags/raw").resolve(rawEntry.get("file").getAsString());
                CompoundTag raw = (CompoundTag) readRawNbt(file);
                assertEquals(registryId, raw.getStringOr("registry", ""));
                assertEquals(tagId, raw.getStringOr("id", ""));
                ListTag values = raw.getListOrEmpty("values");
                assertEquals(tag.get("value_count").getAsInt(), values.size());
                IntArrayTag ids = (IntArrayTag) raw.get("protocol_ids");
                assertNotNull(ids, "protocol_ids is missing for " + key);

                var wireIds = payload == null ? null : payload.tags().get(
                        net.minecraft.resources.Identifier.parse(tagId));
                int[] expectedIds = new int[wireIds == null ? 0 : wireIds.size()];
                for (int index = 0; index < expectedIds.length; index++) {
                    expectedIds[index] = wireIds.getInt(index);
                }
                assertArrayEquals(expectedIds, ids.getAsIntArray(),
                        "raw protocol ids differ from the wire payload for " + key);
                expectedTags++;
            }
        }
        assertEquals(expectedTags, manifest.size(), "raw tag manifest has missing entries");
    }

    @Test
    void rawDatapackNbtMatchesTheCountedWireStream() throws IOException {
        for (String directory : List.of("recipes", "loot", "advancements")) {
            JsonObject document = TestSupport.object(output.resolve(directory + "/minecraft.json"));
            JsonObject manifest = TestSupport.object(
                    output.resolve(directory + "/raw/manifest.json"));
            Map<String, Tag> wire = WireFormatTestSupport.idNbtById(
                    output.resolve(directory + "/minecraft.nbt"));

            assertEquals(document.getAsJsonObject("_meta").get("entry_count").getAsInt(),
                    manifest.size(), directory + " raw manifest count");
            assertEquals(wire.keySet(), manifest.keySet(), directory + " raw manifest ids");

            for (Map.Entry<String, Tag> entry : wire.entrySet()) {
                JsonObject value = manifest.getAsJsonObject(entry.getKey());
                Tag raw = readRawNbt(output.resolve(directory + "/raw")
                        .resolve(value.get("file").getAsString()));
                assertEquals(entry.getValue(), raw, directory + " raw NBT for " + entry.getKey());
            }
        }
    }

    @Test
    void rawCommandTreeIsUnframedAndMatchesTheWirePayload() throws IOException {
        JsonObject manifest = TestSupport.object(output.resolve("commands/raw/manifest.json"));
        JsonObject tree = manifest.getAsJsonObject("tree");
        Path rawFile = output.resolve("commands/raw").resolve(tree.get("file").getAsString());
        byte[] rawBytes = Files.readAllBytes(rawFile);

        assertEquals(0x0A, Byte.toUnsignedInt(rawBytes[0]),
                "raw command NBT must start with its compound type, not a length prefix");
        Tag raw = readRawNbt(rawFile);
        Tag wire = WireFormatTestSupport.readFramedNbt(output.resolve("commands/tree.nbt"));
        assertEquals(wire, raw, "raw command tree differs from the wire tree");
        assertTrue(tree.get("node_count").getAsInt() > 0);
    }

    @Test
    void bothModeKeepsEveryWireNbtByteIdentical() throws IOException {
        Path wireOutput = TestSupport.extraction().output();
        int checked = 0;
        try (Stream<Path> files = Files.walk(wireOutput)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".nbt"))
                    .toList()) {
                String relative = wireOutput.relativize(file).toString().replace('\\', '/');
                Path rawModeFile = output.resolve(relative);
                assertTrue(Files.isRegularFile(rawModeFile),
                        "both mode lost wire NBT " + relative);
                assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(rawModeFile),
                        "both mode changed wire NBT bytes in " + relative);
                checked++;
            }
        }
        assertEquals(160, checked, "expected the complete wire NBT set");
    }

    @Test
    void rawFileNameCollisionGetsADeterministicSuffix() {
        Set<String> used = new HashSet<>();
        assertEquals("minecraft_badlands.nbt",
                RawNbtWriter.uniqueFileName("minecraft:badlands", used));
        String second = RawNbtWriter.uniqueFileName("minecraft:badlands", used);
        assertFalse(second.equals("minecraft_badlands.nbt"));
        assertTrue(second.startsWith("minecraft_badlands-"));
    }

    private static Tag readRawNbt(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        assertFalse(bytes.length == 0, "empty raw NBT: " + file);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            Tag tag = NbtIo.readAnyTag(input, NbtAccounter.unlimitedHeap());
            assertEquals(-1, input.read(), "trailing bytes in raw NBT: " + file);
            return tag;
        }
    }
}
