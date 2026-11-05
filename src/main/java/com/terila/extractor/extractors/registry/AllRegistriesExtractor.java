package com.terila.extractor.extractors.registry;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.RawNbtWriter;
import com.terila.extractor.util.RegistryUtil;
import com.terila.extractor.util.WireFormat;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization.PackedRegistryEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes {@code registries/index.json} plus one document per registry under {@code registries/dump/}.
 *
 * <p>The curated extractors cover the registries the engine consumes directly, each with its own
 * hand picked shape. This extractor is the completeness guarantee: it walks <em>every</em> registry
 * the game has and writes all of them out, exactly once. A registry added by a future Minecraft
 * release is therefore covered without touching this class.
 *
 * <p>A registry is treated as data pack loaded when the loaded registry access holds a different
 * instance than {@link BuiltInRegistries}: the game replaces the built-in placeholder with a new
 * registry while loading the packs. Those are serialised through their element codec
 * ({@link RegistryCodecs}), so entries keep their full payload — a biome keeps its features, a
 * structure its spawn overrides. Everything else is emitted by identity: {@code id} plus
 * {@code protocol_id}, which is the part that matters for anything that is not a data file.
 *
 * <p>{@code registries/index.json} is the manifest: for each registry it records where the dump
 * lives, how many entries it has, whether it came from a data pack, and the protocol id range, so a
 * consumer can verify coverage without opening every file.
 */
public final class AllRegistriesExtractor extends AbstractExtractor {

    /** The registry of registries itself is not data anyone consumes. */
    private static final String ROOT_REGISTRY = "minecraft:root";

    public AllRegistriesExtractor() {
        super("all_registries", DataType.MISC, "registries/index.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, context.lookup());

        Map<String, Registry<?>> builtIn = builtInRegistries();
        Map<String, Registry<?>> loaded = loadedRegistries(context);

        JsonArray index = new JsonArray();
        Set<String> written = new LinkedHashSet<>();
        int totalEntries = 0;
        int datapackCount = 0;
        boolean wireOutput = context.config().nbtFormat().writesWire();
        boolean rawOutput = context.config().nbtFormat().writesRaw();

        // 1. Data pack loaded registries, with their payload.
        for (Map.Entry<String, Registry<?>> entry : loaded.entrySet()) {
            if (builtIn.get(entry.getKey()) == entry.getValue()) {
                continue;
            }
            datapackCount++;
            totalEntries += write(context, entry.getKey(), entry.getValue(), true, "datapack", ops,
                    index, written, wireOutput, rawOutput);
        }

        // 2. Built-in registries, by identity. Ones already written above keep their richer dump.
        for (Map.Entry<String, Registry<?>> entry : builtIn.entrySet()) {
            if (written.contains(entry.getKey())) {
                continue;
            }
            totalEntries += write(context, entry.getKey(), entry.getValue(), false, "builtin", ops,
                    index, written, wireOutput, rawOutput);
        }

        // 3. Safety net: anything the composite access exposes that neither pass produced.
        for (Map.Entry<String, Registry<?>> entry : loaded.entrySet()) {
            if (written.contains(entry.getKey())) {
                continue;
            }
            totalEntries += write(context, entry.getKey(), entry.getValue(), false, "loaded", ops,
                    index, written, wireOutput, rawOutput);
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), written.size()));
        root.add("index", sortedIndex(index));
        root.addProperty("registry_count", written.size());
        root.addProperty("entry_count", totalEntries);
        root.addProperty("datapack_registry_count", datapackCount);
        root.addProperty("builtin_registry_count", builtIn.size());
        if (wireOutput) {
            root.addProperty("nbt_registry_count", index.size());
        }
        if (rawOutput) {
            root.addProperty("raw_registry_count", index.size());
        }
        write(context, root);
        return written.size();
    }

    /** Dumps one registry and appends its manifest entry. */
    private int write(ExtractorContext context, String id, Registry<?> registry, boolean withData,
                      String source, RegistryOps<JsonElement> ops, JsonArray index,
                      Set<String> written, boolean wireOutput, boolean rawOutput) throws IOException {
        JsonObject dump = dump(registry, ops, withData, id, context);
        String path = dumpPath(id);

        // The human-readable dump is always written. Wire and raw are optional projections of the
        // same rows, so switching format cannot change which entries or protocol ids are covered.
        JsonObject document = new JsonObject();
        document.add("_meta", context.meta(name() + ":" + id, dump.get("entry_count").getAsInt()));
        document.addProperty("registry", id);
        document.addProperty("source", source);
        document.add("registries", dump);
        context.writer().write(path, document);

        String nbtPath = wireOutput ? nbtPath(id) : null;
        String rawPath = rawOutput ? rawManifestPath(id) : null;
        // Encoding once and consuming it twice keeps the wire and raw entry order identical.
        List<WireEntry> rows = encodeEntries(context, id, registry, withData);
        if (wireOutput) {
            // Every registry is also written as the exact ClientboundRegistryDataPacket body.
            writeWire(context, nbtPath, id, rows);
        }
        if (rawOutput) {
            writeRaw(context, id, rows);
        }

        written.add(id);
        index.add(describe(id, source, path, nbtPath, rawPath, dump));
        return dump.get("entry_count").getAsInt();
    }

    /**
     * Serialises one registry.
     *
     * @param withData whether each entry should carry its codec payload as well as its id.
     * @return {@code {entry_count, entries: [...]}} plus protocol id bounds.
     */
    @SuppressWarnings("unchecked")
    private <T> JsonObject dump(Registry<?> raw, RegistryOps<JsonElement> ops, boolean withData,
                                String id, ExtractorContext context) {
        Registry<T> registry = (Registry<T>) raw;
        JsonArray entries = new JsonArray();
        int failures = 0;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;

        for (Map.Entry<ResourceKey<T>, T> entry : RegistryUtil.entries(registry)) {
            JsonObject json = new JsonObject();
            json.addProperty("id", entry.getKey().identifier().toString());

            // A negative id means the registry has no protocol identity (a built-in placeholder);
            // the entry is still in the dump, but it must not contribute to the id range.
            int protocolId = registry.getId(entry.getValue());
            if (protocolId >= 0) {
                json.addProperty("protocol_id", protocolId);
                lowest = Math.min(lowest, protocolId);
                highest = Math.max(highest, protocolId);
            }

            if (withData) {
                JsonElement encoded = encode(registry, ops, entry.getValue());
                if (encoded != null) {
                    json.add("data", encoded);
                } else {
                    failures++;
                }
            }
            entries.add(json);
        }

        JsonObject dump = new JsonObject();
        dump.addProperty("entry_count", entries.size());
        if (!entries.isEmpty() && lowest != Integer.MAX_VALUE) {
            dump.addProperty("min_protocol_id", lowest);
            dump.addProperty("max_protocol_id", highest);
        }
        if (failures > 0) {
            dump.addProperty("entries_without_codec_data", failures);
            context.errors().warning("REGISTRY_ENTRY_NOT_SERIALISABLE", name(), id,
                    failures + " entries could not be serialised through their codec");
        }
        dump.add("entries", entries);
        return dump;
    }

    /**
     * Encodes one entry through its element codec; {@code null} when the codec cannot handle it.
     *
     * <p>The result is canonicalised: codec output for component-bearing payloads (loot tables,
     * advancements) iterates maps keyed on identity, so its member order changes between JVMs. See
     * {@link com.terila.extractor.util.JsonUtil}.
     */
    private static <T> JsonElement encode(Registry<T> registry, RegistryOps<JsonElement> ops, T value) {
        Codec<T> codec = RegistryCodecs.elementCodec(registry);
        if (codec == null) {
            return null;
        }
        try {
            JsonElement encoded = codec.encodeStart(ops, value).result().orElse(null);
            return com.terila.extractor.util.JsonUtil.canonical(encoded);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static JsonObject describe(String id, String source, String path, String nbtPath,
                                       String rawPath, JsonObject dump) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("source", source);
        entry.addProperty("entry_count", dump.get("entry_count").getAsInt());
        entry.addProperty("dump", path);
        if (nbtPath != null) {
            entry.addProperty("nbt", nbtPath);
        }
        if (rawPath != null) {
            entry.addProperty("raw", rawPath);
        }
        if (dump.has("min_protocol_id")) {
            entry.addProperty("min_protocol_id", dump.get("min_protocol_id").getAsInt());
            entry.addProperty("max_protocol_id", dump.get("max_protocol_id").getAsInt());
        }
        return entry;
    }

    /**
     * Orders the manifest by registry identifier.
     *
     * <p>The passes visit data pack registries first and built-in ones second, which is a useful way
     * to work but not a useful reading order; sorting makes the manifest diffable.
     */
    private static JsonArray sortedIndex(JsonArray index) {
        java.util.List<JsonElement> entries = new java.util.ArrayList<>();
        index.forEach(entries::add);
        entries.sort(java.util.Comparator.comparing(
                entry -> entry.getAsJsonObject().get("id").getAsString()));
        JsonArray sorted = new JsonArray();
        entries.forEach(sorted::add);
        return sorted;
    }

    /** {@code minecraft:worldgen/biome} -> {@code registries/dump/minecraft/worldgen/biome.json}. */
    static String dumpPath(String registryId) {
        return path("registries/dump/", registryId, ".json");
    }

    /** {@code minecraft:worldgen/biome} -> {@code registries/nbt/minecraft/worldgen/biome.nbt}. */
    static String nbtPath(String registryId) {
        return path("registries/nbt/", registryId, ".nbt");
    }

    /** {@code minecraft:worldgen/biome} -> {@code registries/raw/minecraft/worldgen/biome/}. */
    static String rawDirectory(String registryId) {
        return path("registries/raw/", registryId, "/");
    }

    /** Manifest that maps every entry id to its raw NBT file. */
    static String rawManifestPath(String registryId) {
        return rawDirectory(registryId) + "manifest.json";
    }

    private static String path(String prefix, String registryId, String suffix) {
        int colon = registryId.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : registryId.substring(0, colon);
        String path = colon < 0 ? registryId : registryId.substring(colon + 1);
        return prefix + namespace + "/" + path + suffix;
    }

    /**
     * Writes the exact body of a {@code ClientboundRegistryDataPacket}.
     *
     * <p>The wire format is {@code registry_id String}, {@code entry_count VarInt}, then each entry's
     * {@code id String}, {@code has_nbt bool}, and optional unnamed NBT. Entry order is protocol id
     * order, and built-in registries deliberately carry no NBT. Encoding through the game's own
     * stream codec keeps the byte layout identical to what the client receives.
     */
    private static <T> List<WireEntry> encodeEntries(ExtractorContext context, String registryId,
                                                     Registry<T> registry, boolean withData) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, context.lookup());
        Codec<T> codec = withData ? RegistryCodecs.elementCodec(registry) : null;
        if (withData && codec == null) {
            context.errors().error("REGISTRY_WIRE_CODEC_MISSING", "all_registries", registryId, null,
                    "A data pack registry has no element codec; its wire payload would lose all NBT");
        }

        List<WireEntry> rows = new ArrayList<>();
        for (Map.Entry<ResourceKey<T>, T> entry : RegistryUtil.entries(registry)) {
            int protocolId = registry.getId(entry.getValue());
            Optional<Tag> data = Optional.empty();
            if (codec != null) {
                Tag payload = codec.encodeStart(ops, entry.getValue()).result().orElse(null);
                if (payload == null) {
                    context.errors().error("REGISTRY_WIRE_ENTRY_MISSING", "all_registries",
                            registryId + "/" + entry.getKey().identifier(), null,
                            "Registry entry has no NBT payload for the wire format");
                } else {
                    // Compound keys are unordered on the wire; sort them so two runs emit the same bytes.
                    data = Optional.of(WireFormat.canonicalNbt(payload));
                }
            }
            rows.add(new WireEntry(protocolId, new PackedRegistryEntry(
                    entry.getKey().identifier(), data)));
        }
        rows.sort(Comparator.comparingInt(WireEntry::protocolId));
        return rows;
    }

    /** Writes the exact body of a {@code ClientboundRegistryDataPacket}. */
    private static void writeWire(ExtractorContext context, String relativePath, String registryId,
                                  List<WireEntry> rows) throws IOException {
        List<PackedRegistryEntry> packed = rows.stream().map(WireEntry::entry).toList();
        ResourceKey<? extends Registry<?>> key = ResourceKey.createRegistryKey(
                Identifier.parse(registryId));
        ClientboundRegistryDataPacket packet = new ClientboundRegistryDataPacket(key, packed);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundRegistryDataPacket.STREAM_CODEC.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            Path target = context.writer().resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } finally {
            buffer.release();
        }
    }

    /** Writes one unframed NBT file per entry and the direct-entry manifest. */
    private static void writeRaw(ExtractorContext context, String registryId, List<WireEntry> rows)
            throws IOException {
        String directory = rawDirectory(registryId);
        JsonObject manifest = new JsonObject();
        Set<String> usedNames = new HashSet<>();

        for (WireEntry row : rows) {
            String id = row.entry().id().toString();
            JsonObject item = new JsonObject();
            item.addProperty("protocol_id", row.protocolId());
            if (row.entry().data().isPresent()) {
                // The name is collision-checked per registry; a custom pack can contain two ids that
                // flatten to the same Windows-safe name.
                String file = RawNbtWriter.uniqueFileName(id, usedNames);
                RawNbtWriter.writeNbt(context, directory + file, row.entry().data().orElseThrow());
                item.addProperty("file", file);
            } else {
                // Built-in registries carry identity only; there is no NBT payload to write.
                item.add("file", JsonNull.INSTANCE);
            }
            manifest.add(id, item);
        }

        context.writer().write(directory + "manifest.json", manifest);
    }

    /** A registry entry paired with the protocol id that determines its wire order. */
    private record WireEntry(int protocolId, PackedRegistryEntry entry) {
    }

    /** Every built-in registry, keyed by identifier, in the game's own order. */
    @SuppressWarnings("unchecked")
    private static Map<String, Registry<?>> builtInRegistries() {
        Map<String, Registry<?>> byId = new LinkedHashMap<>();
        Registry<Registry<?>> root = (Registry<Registry<?>>) BuiltInRegistries.REGISTRY;
        for (Registry<?> registry : root) {
            Identifier id = root.getKey(registry);
            if (id != null && !id.toString().equals(ROOT_REGISTRY)) {
                byId.put(id.toString(), registry);
            }
        }
        return byId;
    }

    /** Every registry of the loaded data set, keyed by identifier, sorted by identifier. */
    private static Map<String, Registry<?>> loadedRegistries(ExtractorContext context) {
        Map<String, Registry<?>> byId = new LinkedHashMap<>();
        RegistryUtil.all(context.registryAccess())
                .forEach(entry -> byId.put(entry.key().identifier().toString(), entry.value()));
        return byId;
    }
}
