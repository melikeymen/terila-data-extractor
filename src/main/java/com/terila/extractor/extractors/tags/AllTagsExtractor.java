package com.terila.extractor.extractors.tags;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorVersion;
import com.terila.extractor.core.RawNbtWriter;
import com.terila.extractor.extractors.tags.model.TagData;
import com.terila.extractor.util.StringUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes {@code tags/all.json}: the resolved tags of <em>every</em> registry, in one document.
 *
 * <p>The curated tag extractors cover the categories an engine touches first (blocks, items,
 * entities, fluids, game events, functions and villager trades). Vanilla ships tags for more
 * registries than those — enchantments, damage types, potions, instruments, banner patterns,
 * painting variants, points of interest, timelines, dialogs and the whole {@code worldgen} tree — and
 * a new tag registry would otherwise be invisible until someone added a class for it.
 *
 * <p>This is the completeness guarantee for tags, in the same spirit as
 * {@link com.terila.extractor.extractors.registry.AllRegistriesExtractor} for registries: it walks
 * the tag bindings of every loaded registry rather than a list, so nothing can be missed. The
 * curated documents stay as they are, because their flat shape is what most consumers want; this
 * document is the manifest that proves nothing else exists. A second document,
 * {@code tags/by_entry.json}, inverts the same bindings so a consumer can ask which tags an entry
 * belongs to without scanning the whole table.
 *
 * <pre>
 * { "registry": "minecraft:enchantment", "tag_count": 22, "value_count": 91,
 *   "tags": [ { "id": "minecraft:curse", "values": [...], "replace": false, "value_count": 3 } ] }
 * </pre>
 */
public final class AllTagsExtractor extends AbstractExtractor {

    public AllTagsExtractor() {
        super("all_tags", DataType.TAGS, "tags/all.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Map<String, JsonObject> groups = new TreeMap<>();
        Map<String, Map<String, TreeSet<String>>> entriesByRegistry = new TreeMap<>();
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> payloads =
                new TreeMap<>(Comparator.comparing(key -> key.identifier().toString()));
        int tagCount = 0;
        int valueCount = 0;

        // The live tag bindings are the authority. Walking every loaded registry means a custom
        // datapack tag is included even when no curated extractor knows that registry.
        for (RegistryAccess.RegistryEntry<?> entry : context.registryAccess().registries().toList()) {
            List<TagData> tags = tags(entry.value());
            if (tags.isEmpty()) {
                continue;
            }

            JsonArray array = new JsonArray();
            Map<String, TreeSet<String>> byEntry = new TreeMap<>();
            int values = 0;
            for (TagData tag : tags) {
                array.add(tag.toJson());
                values += tag.values().size();
                for (String value : tag.values()) {
                    byEntry.computeIfAbsent(value, ignored -> new TreeSet<>()).add(tag.id());
                }
            }

            JsonObject group = new JsonObject();
            group.addProperty("registry", entry.key().identifier().toString());
            group.addProperty("tag_count", tags.size());
            group.addProperty("value_count", values);
            group.add("tags", array);
            groups.put(group.get("registry").getAsString(), group);
            entriesByRegistry.put(group.get("registry").getAsString(), byEntry);
            TagNetworkSerialization.NetworkPayload payload = networkPayload(entry.value());
            if (!payload.isEmpty()) {
                payloads.put(entry.key(), payload);
            }

            tagCount += tags.size();
            valueCount += values;
        }

        JsonArray data = new JsonArray();
        groups.values().forEach(data::add);

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), tagCount));
        root.addProperty("source", "minecraft:tag-bindings");
        root.addProperty("registry_count", groups.size());
        root.addProperty("tag_count", tagCount);
        root.addProperty("value_count", valueCount);
        root.add("data", data);
        write(context, root);
        if (context.config().nbtFormat().writesWire()) {
            writeWire(context, payloads, clientRegistries(context));
        }
        if (context.config().nbtFormat().writesRaw()) {
            writeRaw(context, groups, payloads);
        }
        writeByEntry(context, entriesByRegistry);

        if (groups.isEmpty()) {
            context.errors().warning("TAGS_UNAVAILABLE", name(), "tags/all.json",
                    "No registry has tag bindings; the loaded data set may not have bound its tags");
        }
        return tagCount;
    }

    /**
     * Resolves the ordered client tag registry whitelist and reports drift from the game.
     *
     * <p>The configuration is the source of truth, because it is a capture of a vanilla packet and a
     * capture can include registries this extractor has no tag binding for. The game's own derivation
     * is compared against it as a diagnostic: a mismatch is reported so a stale list is visible, but
     * it never replaces the capture or fails the run.
     */
    private static List<String> clientRegistries(ExtractorContext context) throws IOException {
        Optional<ClientTagRegistries.Config> configuration = ClientTagRegistries.load();
        List<String> registries;

        if (configuration.isPresent()) {
            ClientTagRegistries.Config config = configuration.get();
            registries = config.registries();
            if (config.protocol() != ExtractorVersion.PROTOCOL_VERSION) {
                context.errors().warning("TAG_REGISTRIES_PROTOCOL_MISMATCH", "all_tags",
                        ClientTagRegistries.configPath().toString(),
                        "Configuration targets protocol " + config.protocol()
                                + " but this run targets " + ExtractorVersion.PROTOCOL_VERSION);
            }
            context.layers().ifPresent(layers -> {
                String difference = ClientTagRegistries.difference(registries,
                        ClientTagRegistries.derive(layers));
                if (difference != null) {
                    context.errors().warning("TAG_REGISTRY_DRIFT", "all_tags",
                            ClientTagRegistries.configPath().toString(),
                            "Client tag registries differ from the game's own update_tags set ("
                                    + difference + ")");
                }
            });
        } else {
            registries = context.layers().map(ClientTagRegistries::derive).orElse(List.of());
            context.errors().warning("TAG_REGISTRIES_MISSING", "all_tags",
                    ClientTagRegistries.configPath().toString(),
                    "No client tag registry configuration; derived " + registries.size()
                            + " registries from the game: " + registries);
        }
        return registries;
    }

    /**
     * Writes the update-tags wire artifacts.
     *
     * <p>{@code tags/all.nbt} is the exact body of {@code ClientboundUpdateTagsPacket}: every
     * registry that declares at least one tag, including tags with no entries (vanilla keeps those;
     * the client expects them), plus any registry in the client whitelist that declares no tag at
     * all, written with {@code tag_count = 0}. Registries, tags and ids are sorted so two runs of the
     * same data are byte-identical.
     *
     * <p>{@code tags/client_tags.bin} is the same format restricted to the whitelist in its captured
     * order. Every retained registry block is copied byte-for-byte from the payload above, so a
     * consumer can compare the two files without re-encoding anything.
     */
    private static void writeWire(ExtractorContext context,
                                  Map<ResourceKey<? extends Registry<?>>,
                                          TagNetworkSerialization.NetworkPayload> payloads,
                                  List<String> clientRegistries)
            throws IOException {
        Set<String> present = new HashSet<>();
        for (ResourceKey<? extends Registry<?>> key : payloads.keySet()) {
            present.add(key.identifier().toString());
        }
        for (String registry : clientRegistries) {
            if (!present.contains(registry)) {
                payloads.put(ResourceKey.createRegistryKey(Identifier.parse(registry)),
                        TagNetworkSerialization.NetworkPayload.EMPTY);
            }
        }

        byte[] payload = TagWirePayload.encode(payloads);
        writeBytes(context, "tags/all.nbt", payload);

        if (clientRegistries.isEmpty()) {
            context.errors().warning("CLIENT_TAGS_EMPTY", "all_tags",
                    ClientTagRegistries.OUTPUT_FILE,
                    "No client tag registry whitelist is configured; the client payload was skipped");
            return;
        }
        writeBytes(context, ClientTagRegistries.OUTPUT_FILE,
                TagWirePayload.compose(clientRegistries, TagWirePayload.blocks(payload)));
    }

    /** Writes raw bytes next to the documents; binary payloads cannot go through the JSON writer. */
    private static void writeBytes(ExtractorContext context, String path, byte[] bytes)
            throws IOException {
        Path target = context.writer().resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    /**
     * Splits the tag payload into self-describing raw NBT documents.
     *
     * <p>A tag has no entry NBT of its own; the raw document therefore carries the resolved ids,
     * their protocol ids and the registry needed to interpret them. This is deliberately lossless:
     * it keeps every tag, including the ones with no entries.
     */
    private static void writeRaw(ExtractorContext context, Map<String, JsonObject> groups,
                                 Map<ResourceKey<? extends Registry<?>>,
                                         TagNetworkSerialization.NetworkPayload> payloads)
            throws IOException {
        Map<String, TagNetworkSerialization.NetworkPayload> payloadsById = new TreeMap<>();
        payloads.forEach((key, payload) -> payloadsById.put(key.identifier().toString(), payload));

        JsonObject manifest = new JsonObject();
        for (Map.Entry<String, JsonObject> groupEntry : groups.entrySet()) {
            String registryId = groupEntry.getKey();
            // Flatten the registry id for the directory name, then keep that same relative
            // directory in the manifest so a consumer can open the file without guessing.
            String registryDirectory = "tags/raw/" + StringUtil.toFileName(registryId) + "/";
            Set<String> usedNames = new HashSet<>();
            TagNetworkSerialization.NetworkPayload payload = payloadsById.get(registryId);

            for (JsonElement element : groupEntry.getValue().getAsJsonArray("tags")) {
                JsonObject tagJson = element.getAsJsonObject();
                String tagId = tagJson.get("id").getAsString();
                String file = RawNbtWriter.uniqueFileName(tagId, usedNames);
                String relativeFile = StringUtil.toFileName(registryId) + "/" + file;

                ListTag values = new ListTag();
                for (JsonElement value : tagJson.getAsJsonArray("values")) {
                    values.add(StringTag.valueOf(value.getAsString()));
                }

                // A tag that only exists in a custom pack may not have a protocol payload; it is
                // still written, with an empty protocol_ids list rather than being dropped.
                IntList protocolIds = payload == null ? null
                        : payload.tags().get(Identifier.parse(tagId));
                int[] ids = new int[protocolIds == null ? 0 : protocolIds.size()];
                for (int index = 0; index < ids.length; index++) {
                    ids[index] = protocolIds.getInt(index);
                }

                CompoundTag raw = new CompoundTag();
                raw.putString("registry", registryId);
                raw.putString("id", tagId);
                raw.putBoolean("replace", tagJson.get("replace").getAsBoolean());
                raw.put("values", values);
                raw.put("protocol_ids", new IntArrayTag(ids));
                RawNbtWriter.writeNbt(context, registryDirectory + file, raw);

                JsonObject item = new JsonObject();
                item.addProperty("registry", registryId);
                item.addProperty("tag", tagId);
                item.addProperty("file", relativeFile);
                item.addProperty("entry_count", ids.length);
                manifest.add(registryId + "|" + tagId, item);
            }
        }

        context.writer().write("tags/raw/manifest.json", manifest);
    }

    /** Builds the numeric-id tag map the update-tags codec expects. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TagNetworkSerialization.NetworkPayload networkPayload(Registry<?> registry) {
        Registry<Object> raw = (Registry) registry;
        Map<Identifier, IntList> tags = new TreeMap<>(Comparator.comparing(Identifier::toString));
        raw.getTags().forEach(named -> {
            IntArrayList ids = new IntArrayList();
            for (Holder<?> holder : named) {
                int id = raw.getId(holder.value());
                if (id >= 0) {
                    ids.add(id);
                }
            }
            ids.sort(null);
            // Vanilla keeps an empty tag: "nothing matches" is data, and a registry whose tags are
            // all empty is still a registry the client expects to receive in the packet.
            tags.put(named.key().location(), ids);
        });
        return new TagNetworkSerialization.NetworkPayload(tags);
    }

    /** Writes the inverse lookup: registry entry id to the tags that contain it. */
    private void writeByEntry(ExtractorContext context,
                              Map<String, Map<String, TreeSet<String>>> byRegistry)
            throws IOException {
        JsonArray data = new JsonArray();
        int entryCount = 0;
        int membershipCount = 0;

        for (Map.Entry<String, Map<String, TreeSet<String>>> registry : byRegistry.entrySet()) {
            JsonArray entries = new JsonArray();
            int memberships = 0;

            for (Map.Entry<String, TreeSet<String>> entry : registry.getValue().entrySet()) {
                JsonArray tags = new JsonArray();
                entry.getValue().forEach(tags::add);

                JsonObject json = new JsonObject();
                json.addProperty("id", entry.getKey());
                json.addProperty("tag_count", tags.size());
                json.add("tags", tags);
                entries.add(json);
                memberships += tags.size();
            }

            JsonObject group = new JsonObject();
            group.addProperty("registry", registry.getKey());
            group.addProperty("entry_count", entries.size());
            group.addProperty("membership_count", memberships);
            group.add("entries", entries);
            data.add(group);

            entryCount += entries.size();
            membershipCount += memberships;
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), entryCount));
        root.addProperty("source", "minecraft:tag-bindings");
        root.addProperty("registry_count", byRegistry.size());
        root.addProperty("entry_count", entryCount);
        root.addProperty("membership_count", membershipCount);
        root.add("data", data);
        context.writer().write("tags/by_entry.json", root);
    }

    /**
     * Resolves every bound tag of one registry.
     *
     * <p>Reading the bindings rather than the datapack files means nested {@code #tag} references are
     * already flattened by the game, so the values are exactly what a consumer would match against.
     * Values and tags are sorted so two runs of the same data are byte-identical.
     */
    private static List<TagData> tags(Registry<?> registry) {
        List<TagData> tags = new ArrayList<>();
        registry.getTags().forEach(named -> {
            List<String> values = new ArrayList<>();
            for (Holder<?> holder : named) {
                holder.unwrapKey().ifPresent(key -> values.add(key.identifier().toString()));
            }
            values.sort(Comparator.naturalOrder());
            tags.add(new TagData(named.key().location().toString(), List.copyOf(values), false));
        });
        tags.sort(Comparator.comparing(TagData::id));
        return tags;
    }
}

