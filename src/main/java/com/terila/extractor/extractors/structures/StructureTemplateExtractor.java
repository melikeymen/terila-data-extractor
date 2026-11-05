package com.terila.extractor.extractors.structures;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes {@code structures/index.json} plus one document per structure template under
 * {@code structures/templates/}.
 *
 * <p>Structure templates are the actual builds — villages, bastions, trial chambers, ruined portals,
 * ships, fossils: 1511 {@code .nbt} files shipped inside the vanilla data pack. They are invisible to
 * the registry API (a structure only names a template pool, and a pool only names resources), which
 * is why a registry-only extractor silently loses every building in the game.
 *
 * <p>Each template is decoded once, then written in a form that is cheap to consume: block positions
 * are grouped under the block state they use, so a reader gets one position array per state instead
 * of thousands of one-off records, while block entities and entities keep their full NBT.
 *
 * <p>A template may ship several palettes ({@code palettes} in the NBT) instead of a single
 * {@code palette}. Shipwrecks do exactly that: the same hull with oak, spruce, birch, jungle, acacia,
 * dark oak, mangrove and cherry variants. The top level always describes the first palette, and
 * {@code palettes} lists every variant when there is more than one, so no variant is lost.
 *
 * <pre>
 * { "id": "minecraft:village/plains/houses/plains_small_house_1",
 *   "size": { "x": 7, "y": 6, "z": 6 },
 *   "block_count": 141,
 *   "palette_count": 1,
 *   "palette": ["minecraft:oak_planks", "minecraft:white_terracotta"],
 *   "blocks": { "minecraft:oak_planks": [[0,0,0],[0,0,1]], ... },
 *   "block_entities": [ { "pos": [0,1,0], "state_index": 0,
 *                         "state": "minecraft:chest", "nbt": { ... } } ],
 *   "entities": [ { "id": "minecraft:villager", "pos": { ... }, "nbt": { ... } } ] }
 * </pre>
 */
public final class StructureTemplateExtractor extends AbstractExtractor {

    /** Pack directory the templates live in. */
    private static final String DIRECTORY = "structure";

    private static final String EXTENSION = ".nbt";

    public StructureTemplateExtractor() {
        super("structure_templates", DataType.STRUCTURES, "structures/index.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        ResourceManager resources = context.resources().orElse(null);
        if (resources == null) {
            context.errors().warning("RESOURCES_UNAVAILABLE", name(), DIRECTORY,
                    "Structure templates are pack resources; no resource manager is available");
            return 0;
        }

        Map<Identifier, Resource> found = new TreeMap<>(resources.listResources(DIRECTORY,
                id -> id.getPath().endsWith(EXTENSION)));
        if (found.isEmpty()) {
            context.errors().warning("STRUCTURE_TEMPLATES_MISSING", name(), DIRECTORY,
                    "No structure templates found in the loaded packs");
            return 0;
        }

        JsonArray index = new JsonArray();
        JsonObject groups = new JsonObject();
        long totalBlocks = 0;
        long totalBytes = 0;
        int failures = 0;

        for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
            String templateId = templateId(entry.getKey());
            JsonObject template;
            try (InputStream stream = entry.getValue().open()) {
                byte[] raw = stream.readAllBytes();
                totalBytes += raw.length;
                CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(raw),
                        NbtAccounter.unlimitedHeap());
                template = decode(templateId, tag, entry.getValue().sourcePackId(),
                        entry.getKey().toString(), raw.length);
            } catch (IOException | RuntimeException failure) {
                failures++;
                context.errors().error("STRUCTURE_TEMPLATE_FAILED", name(), templateId, null,
                        String.valueOf(failure));
                continue;
            }

            int blocks = template.get("block_count").getAsInt();
            totalBlocks += blocks;

            String path = "structures/templates/" + namespaceOf(templateId) + "/"
                    + pathOf(templateId) + ".json";
            context.writer().write(path, template);

            JsonObject summary = new JsonObject();
            summary.addProperty("id", templateId);
            summary.addProperty("file", path);
            JsonObject size = template.getAsJsonObject("size");
            summary.addProperty("size_x", size.get("x").getAsInt());
            summary.addProperty("size_y", size.get("y").getAsInt());
            summary.addProperty("size_z", size.get("z").getAsInt());
            summary.addProperty("volume", template.get("volume").getAsInt());
            summary.addProperty("block_count", blocks);
            summary.addProperty("palette_count", template.get("palette_count").getAsInt());
            summary.addProperty("distinct_block_states", template.get("distinct_block_states").getAsInt());
            summary.addProperty("block_entity_count", template.get("block_entity_count").getAsInt());
            summary.addProperty("entity_count", template.get("entity_count").getAsInt());
            summary.addProperty("jigsaw_count", template.get("jigsaw_count").getAsInt());
            summary.addProperty("source_bytes", template.get("source_bytes").getAsInt());
            index.add(summary);

            String group = groupOf(templateId);
            groups.addProperty(group, groups.has(group) ? groups.get(group).getAsInt() + 1 : 1);
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), index.size()));
        root.addProperty("source", DIRECTORY + "/**/*.nbt");
        root.addProperty("template_count", index.size());
        root.addProperty("total_block_count", totalBlocks);
        root.addProperty("total_source_bytes", totalBytes);
        root.addProperty("failed_templates", failures);
        root.add("groups", sorted(groups));
        root.add("templates", index);
        write(context, root);

        if (failures > 0) {
            context.errors().warning("STRUCTURE_TEMPLATE_INCOMPLETE", name(), null,
                    failures + " structure template(s) could not be decoded");
        }
        return index.size();
    }

    /**
     * Decodes one template into its output document.
     *
     * <p>Package visible so the NBT decoding can be tested directly against hand-built tags, without
     * having to read a 1500 file data pack for every case.
     */
    static JsonObject decode(String id, CompoundTag tag, String packId, String resourcePath,
                             int sourceBytes) {
        JsonObject template = new JsonObject();
        template.addProperty("id", id);
        template.addProperty("pack", packId);
        template.addProperty("resource", resourcePath);
        template.addProperty("source_bytes", sourceBytes);

        int[] raw = readInts(tag.get("size"));
        int[] reloaded = reloadedSize(tag);
        int[] size = reloaded != null ? reloaded : raw;

        JsonObject sizeJson = new JsonObject();
        sizeJson.addProperty("x", size[0]);
        sizeJson.addProperty("y", size[1]);
        sizeJson.addProperty("z", size[2]);
        template.add("size", sizeJson);
        template.addProperty("volume", size[0] * size[1] * size[2]);
        template.addProperty("declared_size", "[" + raw[0] + "," + raw[1] + "," + raw[2] + "]");

        tag.getInt("DataVersion").ifPresent(version -> template.addProperty("data_version", version));
        tag.getString("author").ifPresent(author -> template.addProperty("author", author));

        List<List<String>> paletteStates = readPalettes(tag);
        int paletteCount = paletteStates.size();
        if (paletteStates.isEmpty()) {
            // A malformed template with no palette still has to produce a document; every block
            // resolves to air, which is exactly what the game would place.
            paletteStates.add(List.of());
        }

        List<Map<String, JsonArray>> blocksByPalette = new ArrayList<>();
        List<Integer> jigsawsByPalette = new ArrayList<>();
        List<Integer> invalidByPalette = new ArrayList<>();
        for (int i = 0; i < paletteStates.size(); i++) {
            blocksByPalette.add(new TreeMap<>());
            jigsawsByPalette.add(0);
            invalidByPalette.add(0);
        }

        JsonArray blockEntities = new JsonArray();
        JsonObject blockEntityCounts = new JsonObject();
        int invalidIndices = 0;

        ListTag blocks = tag.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            int stateIndex = block.getInt("state").orElse(-1);
            int[] pos = readInts(block.get("pos"));
            boolean invalidForAny = false;
            List<String> states = new ArrayList<>(paletteStates.size());

            // One block is projected into every palette. A shipwreck stores its blocks once and
            // switches only the state table, so decoding per palette is what preserves all eight
            // wood variants instead of materialising the first one and losing the rest.
            for (int palette = 0; palette < paletteStates.size(); palette++) {
                List<String> entries = paletteStates.get(palette);
                boolean valid = stateIndex >= 0 && stateIndex < entries.size();
                String state = valid ? entries.get(stateIndex) : "minecraft:air";
                states.add(state);

                if (!valid) {
                    // Keep an explicit air block at the position rather than dropping it: the count
                    // and the invalid-index warning then agree with the source template.
                    invalidForAny = true;
                    invalidByPalette.set(palette, invalidByPalette.get(palette) + 1);
                }
                if (isJigsaw(state)) {
                    jigsawsByPalette.set(palette, jigsawsByPalette.get(palette) + 1);
                }
                blocksByPalette.get(palette)
                        .computeIfAbsent(state, ignored -> new JsonArray())
                        .add(position(pos));
            }
            if (invalidForAny) {
                invalidIndices++;
            }

            if (block.contains("nbt")) {
                CompoundTag nbt = block.getCompoundOrEmpty("nbt");
                JsonObject entity = new JsonObject();
                entity.add("pos", position(pos));
                entity.addProperty("state_index", stateIndex);
                // The top-level document describes the first palette; per-palette state names are
                // listed separately so a consumer can resolve the entity in every variant.
                entity.addProperty("state", states.get(0));
                if (paletteCount > 1) {
                    JsonArray stateArray = new JsonArray();
                    states.forEach(stateArray::add);
                    entity.add("states", stateArray);
                }
                entity.add("nbt", nbtToJson(nbt));
                blockEntities.add(entity);

                String blockEntityId = nbt.getString("id").orElse(states.get(0));
                blockEntityCounts.addProperty(blockEntityId,
                        blockEntityCounts.has(blockEntityId)
                                ? blockEntityCounts.get(blockEntityId).getAsInt() + 1 : 1);
            }
        }

        Map<String, JsonArray> firstBlocks = blocksByPalette.get(0);
        JsonArray firstPalette = distinctPalette(paletteStates.get(0));
        template.addProperty("palette_count", paletteCount);
        template.addProperty("palette_size", firstPalette.size());
        template.add("palette", firstPalette);

        JsonObject blocksJson = new JsonObject();
        firstBlocks.forEach(blocksJson::add);
        template.addProperty("block_count", blocks.size());
        template.addProperty("distinct_block_states", firstBlocks.size());
        template.addProperty("jigsaw_count", jigsawsByPalette.get(0));
        if (invalidIndices > 0) {
            template.addProperty("blocks_with_invalid_palette_index", invalidIndices);
        }
        template.add("blocks", blocksJson);

        if (paletteCount > 1) {
            JsonArray palettes = new JsonArray();
            for (int palette = 0; palette < paletteCount; palette++) {
                palettes.add(paletteDocument(palette, paletteStates.get(palette),
                        blocksByPalette.get(palette), jigsawsByPalette.get(palette),
                        invalidByPalette.get(palette), blocks.size()));
            }
            template.add("palettes", palettes);
        }
        template.addProperty("block_entity_count", blockEntities.size());
        if (!blockEntityCounts.isEmpty()) {
            template.add("block_entity_counts", sorted(blockEntityCounts));
        }
        template.add("block_entities", blockEntities);

        JsonArray entities = new JsonArray();
        JsonObject entityCounts = new JsonObject();
        ListTag entityList = tag.getListOrEmpty("entities");
        for (int i = 0; i < entityList.size(); i++) {
            CompoundTag entity = entityList.getCompoundOrEmpty(i);
            CompoundTag nbt = entity.getCompoundOrEmpty("nbt");
            String entityId = nbt.getString("id").orElse("minecraft:unknown");

            JsonObject out = new JsonObject();
            out.addProperty("id", entityId);
            out.add("pos", realPosition(entity.get("pos")));
            out.add("block_pos", position(readInts(entity.get("blockPos"))));
            out.add("nbt", nbtToJson(nbt));
            entities.add(out);

            entityCounts.addProperty(entityId,
                    entityCounts.has(entityId) ? entityCounts.get(entityId).getAsInt() + 1 : 1);
        }
        template.addProperty("entity_count", entities.size());
        if (!entityCounts.isEmpty()) {
            template.add("entity_counts", sorted(entityCounts));
        }
        template.add("entities", entities);

        return template;
    }

    /**
     * Reads every palette of a template, in the game's own order.
     *
     * <p>26.3 templates may carry either a single {@code palette} or a {@code palettes} list, and
     * the game uses the latter exclusively when it is present. Reading only {@code palette} turned
     * every shipwreck — the 20 templates that ship eight wood variants — into a hull of air.
     *
     * <p>An empty palette is kept as an empty state list rather than dropped, so a block index that
     * points into it is reported instead of silently becoming a valid block of another variant.
     */
    static List<List<String>> readPalettes(CompoundTag tag) {
        List<ListTag> raw = new ArrayList<>();
        ListTag alternates = tag.getList("palettes").orElse(null);
        if (alternates != null) {
            for (int i = 0; i < alternates.size(); i++) {
                raw.add(alternates.getListOrEmpty(i));
            }
        } else {
            raw.add(tag.getListOrEmpty("palette"));
        }

        List<List<String>> palettes = new ArrayList<>(raw.size());
        for (ListTag palette : raw) {
            List<String> states = new ArrayList<>(palette.size());
            for (int i = 0; i < palette.size(); i++) {
                states.add(stateString(palette.getCompoundOrEmpty(i)));
            }
            palettes.add(states);
        }
        return palettes;
    }

    /** The sorted, de-duplicated state list of one palette. */
    private static JsonArray distinctPalette(List<String> states) {
        TreeSet<String> distinct = new TreeSet<>(states);
        JsonArray palette = new JsonArray();
        distinct.forEach(palette::add);
        return palette;
    }

    /** One entry of {@code palettes}: its state table plus the positions that use each state. */
    private static JsonObject paletteDocument(int index, List<String> states,
                                              Map<String, JsonArray> blocks, int jigsaws, int invalid,
                                              int blockCount) {
        JsonObject document = new JsonObject();
        document.addProperty("index", index);

        JsonArray palette = distinctPalette(states);
        document.addProperty("palette_size", palette.size());
        document.add("palette", palette);

        document.addProperty("block_count", blockCount);
        document.addProperty("distinct_block_states", blocks.size());
        document.addProperty("jigsaw_count", jigsaws);
        if (invalid > 0) {
            document.addProperty("blocks_with_invalid_palette_index", invalid);
        }

        JsonObject positions = new JsonObject();
        blocks.forEach(positions::add);
        document.add("blocks", positions);
        return document;
    }

    /**
     * The block id of a rendered state: {@code minecraft:jigsaw[orientation=east_up]} becomes
     * {@code minecraft:jigsaw}.
     *
     * <p>Counting jigsaws needs the id rather than the whole state: a jigsaw always carries an
     * orientation, so comparing the rendered state with the bare name counted zero of the 2 866
     * jigsaw states in the vanilla pack.
     */
    static String blockId(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    private static boolean isJigsaw(String state) {
        return blockId(state).equals("minecraft:jigsaw");
    }

    /**
     * The size the game computes when it loads the template, or {@code null} when it cannot be used.
     *
     * <p>Recomputing keeps an odd template consistent with what the game would do with it. It is only
     * accepted when it is non-degenerate: the game silently yields {@code (0,0,0)} for a tag it can
     * parse but that lacks the fields it expects, and overwriting a perfectly good declared size with
     * zero would lose real data.
     */
    private static int[] reloadedSize(CompoundTag tag) {
        try {
            StructureTemplate probe = new StructureTemplate();
            probe.load(BuiltInRegistries.BLOCK, tag);
            var loaded = probe.getSize();
            if (loaded == null) {
                return null;
            }
            int[] size = {loaded.getX(), loaded.getY(), loaded.getZ()};
            return size[0] == 0 && size[1] == 0 && size[2] == 0 ? null : size;
        } catch (RuntimeException incomplete) {
            // A template the game cannot load is still dumped from its raw tags.
            return null;
        }
    }

    /**
     * Renders a palette entry as {@code minecraft:oak_log[axis=y]}, properties sorted by name.
     *
     * <p>26.3 renamed the palette keys: an entry is now {@code {id, properties}} where older packs
     * and older Minecraft versions wrote {@code {Name, Properties}}. Both spellings are accepted,
     * because a data pack may still ship the old form.
     *
     * <p>Package visible so both spellings can be covered by a test; reading only the old spelling
     * turned every palette entry in the pack into plain {@code minecraft:air}.
     */
    static String stateString(CompoundTag entry) {
        String name = entry.getString("id")
                .or(() -> entry.getString("Name"))
                .or(() -> entry.getString("name"))
                .orElse("minecraft:air");
        CompoundTag properties = entry.contains("properties") ? entry.getCompoundOrEmpty("properties")
                : entry.getCompoundOrEmpty("Properties");
        if (properties.isEmpty()) {
            return name;
        }

        StringBuilder builder = new StringBuilder(name).append('[');
        boolean first = true;
        for (String key : new TreeSet<>(properties.keySet())) {
            if (!first) {
                builder.append(',');
            }
            builder.append(key).append('=').append(properties.getString(key).orElse(""));
            first = false;
        }
        return builder.append(']').toString();
    }

    /** NBT to the equivalent JSON, so a consumer never needs an NBT reader. */
    static JsonElement nbtToJson(Tag tag) {
        return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
    }

    private static JsonArray position(int[] values) {
        JsonArray array = new JsonArray();
        array.add(values[0]);
        array.add(values[1]);
        array.add(values[2]);
        return array;
    }

    private static JsonObject realPosition(Tag tag) {
        double[] values = readDoubles(tag);
        JsonObject json = new JsonObject();
        json.addProperty("x", values[0]);
        json.addProperty("y", values[1]);
        json.addProperty("z", values[2]);
        return json;
    }

    private static JsonObject sorted(JsonObject source) {
        JsonObject result = new JsonObject();
        new TreeMap<>(source.asMap()).forEach(result::add);
        return result;
    }

    /**
     * Reads a coordinate triple.
     *
     * <p>The game writes positions as a list tag in newer templates and as an array tag in older
     * ones, so both representations have to be understood.
     */
    private static int[] readInts(Tag tag) {
        int[] values = {0, 0, 0};
        if (tag instanceof IntArrayTag array) {
            int[] raw = array.getAsIntArray();
            for (int i = 0; i < Math.min(3, raw.length); i++) {
                values[i] = raw[i];
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < Math.min(3, list.size()); i++) {
                values[i] = list.getInt(i).orElse(0);
            }
        }
        return values;
    }

    private static double[] readDoubles(Tag tag) {
        double[] values = {0.0, 0.0, 0.0};
        if (tag instanceof ListTag list) {
            for (int i = 0; i < Math.min(3, list.size()); i++) {
                values[i] = list.getDouble(i).orElse(0.0);
            }
        }
        return values;
    }

    /** {@code minecraft:structure/village/plains/x.nbt} -> {@code minecraft:village/plains/x}. */
    static String templateId(Identifier resource) {
        String path = resource.getPath();
        if (path.startsWith(DIRECTORY + "/")) {
            path = path.substring(DIRECTORY.length() + 1);
        }
        if (path.endsWith(EXTENSION)) {
            path = path.substring(0, path.length() - EXTENSION.length());
        }
        return resource.getNamespace() + ":" + path;
    }

    static String namespaceOf(String id) {
        return com.terila.extractor.util.StringUtil.namespace(id);
    }

    static String pathOf(String id) {
        return com.terila.extractor.util.StringUtil.path(id);
    }

    /** Top level grouping of a template id: {@code minecraft:village/...} -> {@code village}. */
    static String groupOf(String id) {
        String path = pathOf(id);
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }
}

