package com.terila.extractor.extractors.structures;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for decoding a structure template's NBT and for the mapping between a pack resource path and
 * a template identifier.
 *
 * <p>The decoding tests exist because of a real, silent bug: 26.3 renamed the palette keys from
 * {@code {Name, Properties}} to {@code {id, properties}}, and reading only the old spelling turned
 * every palette entry of all 1511 templates into plain {@code minecraft:air}. The output was valid
 * JSON with the wrong content, which is precisely the failure mode a schema check cannot catch.
 */
class StructureTemplateExtractorTest {

    private static final int SOURCE_BYTES = 512;

    @BeforeAll
    static void boot() {
        TestSupport.bootStrap();
    }

    // --- identifier mapping -----------------------------------------------------------------

    @Test
    void stripsTheDirectoryAndTheExtension() {
        assertEquals("minecraft:village/plains/houses/plains_small_house_1",
                StructureTemplateExtractor.templateId(
                        Identifier.parse("minecraft:structure/village/plains/houses/plains_small_house_1.nbt")));
        assertEquals("minecraft:empty",
                StructureTemplateExtractor.templateId(Identifier.parse("minecraft:structure/empty.nbt")));
    }

    @Test
    void keepsForeignNamespaces() {
        assertEquals("terila:custom/hut",
                StructureTemplateExtractor.templateId(Identifier.parse("terila:structure/custom/hut.nbt")));
    }

    @Test
    void splitsNamespacesAndGroups() {
        String id = "minecraft:trial_chambers/chamber/assembly";
        assertEquals("minecraft", StructureTemplateExtractor.namespaceOf(id));
        assertEquals("trial_chambers/chamber/assembly", StructureTemplateExtractor.pathOf(id));
        assertEquals("trial_chambers", StructureTemplateExtractor.groupOf(id));
        assertEquals("empty", StructureTemplateExtractor.groupOf("minecraft:empty"));
    }

    // --- palette decoding -------------------------------------------------------------------

    /** The 26.3 spelling: {@code {id, properties}}. */
    @Test
    void readsTheCurrentPaletteSpelling() {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", "minecraft:oak_log");
        CompoundTag properties = new CompoundTag();
        properties.putString("axis", "y");
        entry.put("properties", properties);

        assertEquals("minecraft:oak_log[axis=y]", StructureTemplateExtractor.stateString(entry));
    }

    /** The pre-26.3 spelling, which a data pack may still ship. */
    @Test
    void readsTheLegacyPaletteSpelling() {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", "minecraft:oak_log");
        CompoundTag properties = new CompoundTag();
        properties.putString("axis", "x");
        entry.put("Properties", properties);

        assertEquals("minecraft:oak_log[axis=x]", StructureTemplateExtractor.stateString(entry));
    }

    /** Properties are sorted so two spellings of the same state render identically. */
    @Test
    void sortsPropertiesByName() {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", "minecraft:wall_torch");
        CompoundTag properties = new CompoundTag();
        properties.putString("facing", "north");
        properties.putString("axis", "x");
        entry.put("properties", properties);

        assertEquals("minecraft:wall_torch[axis=x,facing=north]", StructureTemplateExtractor.stateString(entry));
    }

    /** A palette entry with no properties is just the block name. */
    @Test
    void rendersAStatelessBlockWithoutBrackets() {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", "minecraft:stone");

        assertEquals("minecraft:stone", StructureTemplateExtractor.stateString(entry));
    }

    /** An entry that names no block must not silently become a real one. */
    @Test
    void fallsBackToAirForAnEmptyEntry() {
        assertEquals("minecraft:air", StructureTemplateExtractor.stateString(new CompoundTag()));
    }

    // --- document decoding ------------------------------------------------------------------

    @Test
    void decodesSizePaletteAndBlocks() {
        JsonObject template = StructureTemplateExtractor.decode("minecraft:test/hut",
                template(sizeList(3), List.of("minecraft:stone"), 5), "vanilla", "structure/test/hut.nbt",
                SOURCE_BYTES);

        assertEquals("minecraft:test/hut", template.get("id").getAsString());
        assertEquals("vanilla", template.get("pack").getAsString());
        assertEquals(SOURCE_BYTES, template.get("source_bytes").getAsInt());

        JsonObject size = template.getAsJsonObject("size");
        assertEquals(3, size.get("x").getAsInt());
        assertEquals(3, size.get("y").getAsInt());
        assertEquals(3, size.get("z").getAsInt());
        assertEquals(27, template.get("volume").getAsInt());

        assertEquals(1, template.get("palette_size").getAsInt());
        assertEquals(5, template.get("block_count").getAsInt());
        assertEquals(1, template.get("distinct_block_states").getAsInt());
        assertFalse(template.getAsJsonObject("blocks").isEmpty());
    }

    /** Positions are grouped under the state that uses them, and each is a triple. */
    @Test
    void groupsPositionsByBlockState() {
        JsonObject template = StructureTemplateExtractor.decode("minecraft:test/hut",
                template(sizeList(3), List.of("minecraft:stone"), 4), "vanilla", "structure/test/hut.nbt", 1);

        var positions = template.getAsJsonObject("blocks").getAsJsonArray("minecraft:stone");
        assertEquals(4, positions.size());
        for (var position : positions) {
            assertEquals(3, position.getAsJsonArray().size(), "a position is an [x, y, z] triple");
        }
        assertEquals(4, template.get("block_count").getAsInt());
    }

    /** Both palette entries must appear, and the states have to be resolvable. */
    @Test
    void keepsEveryDistinctStateInThePalette() {
        JsonObject template = StructureTemplateExtractor.decode("minecraft:test/hut",
                template(sizeList(3), List.of("minecraft:stone", "minecraft:oak_log[axis=y]"), 3),
                "vanilla", "structure/test/hut.nbt", 1);

        List<String> palette = template.getAsJsonArray("palette").asList().stream()
                .map(element -> element.getAsString())
                .toList();

        assertEquals(List.of("minecraft:oak_log[axis=y]", "minecraft:stone"), palette);
        assertEquals(2, template.get("distinct_block_states").getAsInt());
        assertEquals(2, template.get("palette_size").getAsInt());
    }

    /** Block entities and entities keep their NBT, converted to JSON so no NBT reader is needed. */
    @Test
    void keepsBlockEntityAndEntityData() {
        CompoundTag tag = template(sizeList(3), List.of("minecraft:chest"), 1);
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("CustomName", "Loot");
        tag.getListOrEmpty("blocks").getCompoundOrEmpty(0).put("nbt", chest);

        ListTag entities = new ListTag();
        CompoundTag entity = new CompoundTag();
        entity.put("pos", new ListTag());
        CompoundTag entityNbt = new CompoundTag();
        entityNbt.putString("id", "minecraft:villager");
        entity.put("nbt", entityNbt);
        entities.add(entity);
        tag.put("entities", entities);

        JsonObject decoded = StructureTemplateExtractor.decode("minecraft:test/hut", tag, "vanilla",
                "structure/test/hut.nbt", 1);

        assertEquals(1, decoded.get("block_entity_count").getAsInt());
        JsonObject blockEntity = decoded.getAsJsonArray("block_entities").get(0).getAsJsonObject();
        assertEquals("minecraft:chest", blockEntity.get("state").getAsString());
        assertEquals("Loot", blockEntity.getAsJsonObject("nbt").get("CustomName").getAsString());

        assertEquals(1, decoded.get("entity_count").getAsInt());
        JsonObject entityJson = decoded.getAsJsonArray("entities").get(0).getAsJsonObject();
        assertEquals("minecraft:villager", entityJson.get("id").getAsString());
        assertTrue(decoded.getAsJsonObject("entity_counts").has("minecraft:villager"));
    }

    /**
     * Every palette of a multi-palette template has to be decoded, not just the first.
     *
     * <p>This is the shipwreck case: the NBT carries {@code palettes} with one variant per wood
     * type. Reading only {@code palette} left the top-level table empty and turned every block into
     * {@code minecraft:air}.
     */
    @Test
    void readsEveryAlternatePalette() {
        CompoundTag tag = multiPaletteTemplate(sizeList(3), List.of(
                List.of("minecraft:oak_planks"),
                List.of("minecraft:spruce_planks"),
                List.of("minecraft:cherry_planks")), 2);

        JsonObject decoded = StructureTemplateExtractor.decode("minecraft:test/ship", tag, "vanilla",
                "structure/test/ship.nbt", 1);

        assertEquals(3, decoded.get("palette_count").getAsInt());
        assertEquals("minecraft:oak_planks", decoded.getAsJsonArray("palette").get(0).getAsString());
        assertEquals(2, decoded.getAsJsonObject("blocks")
                .getAsJsonArray("minecraft:oak_planks").size());
        assertFalse(decoded.has("blocks_with_invalid_palette_index"),
                "a valid alternate palette must not be reported as an invalid index");

        JsonArray palettes = decoded.getAsJsonArray("palettes");
        assertEquals(3, palettes.size());
        assertEquals(2, palettes.get(0).getAsJsonObject().getAsJsonObject("blocks")
                .getAsJsonArray("minecraft:oak_planks").size());
        assertEquals(2, palettes.get(1).getAsJsonObject().getAsJsonObject("blocks")
                .getAsJsonArray("minecraft:spruce_planks").size());
        assertEquals(2, palettes.get(2).getAsJsonObject().getAsJsonObject("blocks")
                .getAsJsonArray("minecraft:cherry_planks").size());
    }

    /** The game uses {@code palettes} exclusively when it exists; the single palette must not win. */
    @Test
    void prefersThePaletteListOverASinglePalette() {
        CompoundTag tag = multiPaletteTemplate(sizeList(3),
                List.of(List.of("minecraft:oak_planks"), List.of("minecraft:spruce_planks")), 1);
        tag.put("palette", paletteTag(List.of("minecraft:stone")));

        JsonObject decoded = StructureTemplateExtractor.decode("minecraft:test/ship", tag, "vanilla",
                "structure/test/ship.nbt", 1);

        assertEquals(2, decoded.get("palette_count").getAsInt());
        assertEquals("minecraft:oak_planks", decoded.getAsJsonArray("palette").get(0).getAsString());
        assertFalse(decoded.getAsJsonObject("blocks").has("minecraft:stone"));
    }

    /** A block entity keeps its state index and the state it resolves to in every palette. */
    @Test
    void keepsBlockEntityStateForEveryPalette() {
        CompoundTag tag = multiPaletteTemplate(sizeList(3),
                List.of(List.of("minecraft:chest"), List.of("minecraft:barrel")), 1);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:chest");
        tag.getListOrEmpty("blocks").getCompoundOrEmpty(0).put("nbt", nbt);

        JsonObject decoded = StructureTemplateExtractor.decode("minecraft:test/ship", tag, "vanilla",
                "structure/test/ship.nbt", 1);
        JsonObject blockEntity = decoded.getAsJsonArray("block_entities").get(0).getAsJsonObject();

        assertEquals(0, blockEntity.get("state_index").getAsInt());
        assertEquals("minecraft:chest", blockEntity.get("state").getAsString());
        assertEquals("minecraft:chest", blockEntity.getAsJsonArray("states").get(0).getAsString());
        assertEquals("minecraft:barrel", blockEntity.getAsJsonArray("states").get(1).getAsString());
    }

    /** Jigsaw blocks carry an orientation, so the counter must compare the block id, not the state. */
    @Test
    void countsJigsawBlocksWithProperties() {
        JsonObject decoded = StructureTemplateExtractor.decode("minecraft:test/hut",
                template(sizeList(3), List.of("minecraft:jigsaw[orientation=east_up]"), 2),
                "vanilla", "structure/test/hut.nbt", 1);

        assertEquals(2, decoded.get("jigsaw_count").getAsInt());
        assertEquals("minecraft:jigsaw", StructureTemplateExtractor.blockId(
                "minecraft:jigsaw[orientation=east_up]"));
    }

    /** An out-of-range palette index must not be reported as a block that does not exist. */
    @Test
    void flagsBlocksWithAnInvalidPaletteIndex() {
        CompoundTag tag = template(sizeList(3), List.of("minecraft:stone"), 1);
        tag.getListOrEmpty("blocks").getCompoundOrEmpty(0).putInt("state", 99);

        JsonObject decoded = StructureTemplateExtractor.decode("minecraft:test/hut", tag, "vanilla",
                "structure/test/hut.nbt", 1);

        assertEquals(1, decoded.get("blocks_with_invalid_palette_index").getAsInt());
        assertTrue(decoded.getAsJsonObject("blocks").has("minecraft:air"));
    }

    /**
     * The size is read from either representation the game has used.
     *
     * <p>The int array form is what older templates wrote, and the game yields {@code (0,0,0)} for it
     * when it reloads the template — so the declared size has to win over a degenerate reload.
     */
    @Test
    void readsSizesFromBothTagRepresentations() {
        CompoundTag asArray = template(sizeArray(4), List.of("minecraft:stone"), 0);
        CompoundTag asList = template(sizeList(2), List.of("minecraft:stone"), 0);

        assertEquals(4, StructureTemplateExtractor.decode("minecraft:a", asArray, "v", "p", 1)
                .getAsJsonObject("size").get("x").getAsInt());
        assertEquals(2, StructureTemplateExtractor.decode("minecraft:a", asList, "v", "p", 1)
                .getAsJsonObject("size").get("x").getAsInt());
    }

    // --- NBT builders -----------------------------------------------------------------------

    /** {@code [n,n,n]} as a list tag, the representation the game writes today. */
    private static ListTag sizeList(int size) {
        ListTag tag = new ListTag();
        for (int i = 0; i < 3; i++) {
            tag.add(net.minecraft.nbt.IntTag.valueOf(size));
        }
        return tag;
    }

    /** {@code [n,n,n]} as an int array tag, the representation older templates used. */
    private static IntArrayTag sizeArray(int size) {
        return new IntArrayTag(new int[] {size, size, size});
    }

    /**
     * A minimal template: a size, a palette, and {@code blockCount} blocks on a diagonal.
     *
     * @param size       the declared size, as either of the two representations the game has used.
     * @param palette    palette entries, written with the current spelling where they contain one.
     * @param blockCount how many blocks to place.
     */
    private static CompoundTag template(net.minecraft.nbt.Tag size, List<String> palette, int blockCount) {
        CompoundTag tag = new CompoundTag();
        tag.put("size", size);

        tag.put("palette", paletteTag(palette));
        tag.put("blocks", diagonalBlocks(palette.size(), blockCount));
        return tag;
    }

    /** A template that uses the {@code palettes} list, the multi-variant form shipwrecks use. */
    private static CompoundTag multiPaletteTemplate(net.minecraft.nbt.Tag size,
                                                    List<List<String>> palettes, int blockCount) {
        CompoundTag tag = new CompoundTag();
        tag.put("size", size);

        ListTag paletteList = new ListTag();
        for (List<String> palette : palettes) {
            paletteList.add(paletteTag(palette));
        }
        tag.put("palettes", paletteList);
        tag.put("blocks", diagonalBlocks(palettes.get(0).size(), blockCount));
        return tag;
    }

    /** Builds one palette list from rendered states. */
    private static ListTag paletteTag(List<String> palette) {
        ListTag paletteTag = new ListTag();
        for (String state : palette) {
            CompoundTag entry = new CompoundTag();
            int bracket = state.indexOf('[');
            entry.putString("id", bracket < 0 ? state : state.substring(0, bracket));
            if (bracket >= 0) {
                CompoundTag properties = new CompoundTag();
                for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
                    int equals = pair.indexOf('=');
                    properties.putString(pair.substring(0, equals), pair.substring(equals + 1));
                }
                entry.put("properties", properties);
            }
            paletteTag.add(entry);
        }
        return paletteTag;
    }

    /** {@code blockCount} blocks on a diagonal, cycling through the palette indices. */
    private static ListTag diagonalBlocks(int paletteSize, int blockCount) {
        ListTag blocks = new ListTag();
        for (int i = 0; i < blockCount; i++) {
            CompoundTag block = new CompoundTag();
            block.putInt("state", i % paletteSize);
            block.put("pos", new IntArrayTag(new int[] {i, i, i}));
            blocks.add(block);
        }
        return blocks;
    }
}

