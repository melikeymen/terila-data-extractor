package com.terila.extractor.extractors.blocks;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.blocks.model.BlockShapeData;
import com.terila.extractor.util.MathUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes {@code blocks/shapes.json}: collision and outline geometry as axis-aligned boxes.
 *
 * <p>For every block every distinct geometry reachable from its states is emitted, so the engine can
 * cache shapes by identity. Collision and outline are recorded separately because they are different
 * questions: a button has no collision box but does have an outline, and a slab's outline is taller
 * than its collision box when it is a bottom slab in a double-block position.
 *
 * <p>Shapes are evaluated against an empty world, which makes them a property of the state alone.
 * Shapes that depend on neighbours (a fence's connections, a wall's sides) are therefore reported in
 * their unconnected form.
 */
public final class BlockShapeExtractor extends AbstractExtractor {

    public BlockShapeExtractor() {
        super("block_shapes", DataType.BLOCKS, "blocks/shapes.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        var registry = context.registry(Registries.BLOCK).orElse(BuiltInRegistries.BLOCK);

        JsonObject root = new JsonObject();
        JsonObject blocks = new JsonObject();
        int entries = 0;

        for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> entry
                : com.terila.extractor.util.RegistryUtil.entries(registry)) {
            Block block = entry.getValue();
            String id = entry.getKey().identifier().toString();
            try {
                Map<String, BlockShapeData> distinct = new LinkedHashMap<>();
                JsonArray stateKeys = new JsonArray();
                Map<String, BlockShapeData> outlineDistinct = new LinkedHashMap<>();
                JsonArray outlineKeys = new JsonArray();
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    BlockShapeData shape = describe(
                            state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                    String key = key(shape);
                    distinct.putIfAbsent(key, shape);
                    stateKeys.add(key);

                    BlockShapeData outline = describe(
                            state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                    String outlineKey = key(outline);
                    outlineDistinct.putIfAbsent(outlineKey, outline);
                    outlineKeys.add(outlineKey);
                }

                JsonObject blockJson = new JsonObject();
                blockJson.add("distinct_shapes", shapes(distinct));
                blockJson.add("state_shape_keys", stateKeys);
                blockJson.addProperty("default_shape_key", key(describe(block.defaultBlockState()
                        .getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))));
                blockJson.add("distinct_outline_shapes", shapes(outlineDistinct));
                blockJson.add("state_outline_keys", outlineKeys);
                blockJson.addProperty("default_outline_key", key(describe(block.defaultBlockState()
                        .getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))));
                blocks.add(id, blockJson);
                entries++;
            } catch (RuntimeException failure) {
                context.errors().error("SHAPE_EXTRACTION_FAILED", name(), id, null, String.valueOf(failure));
            }
        }

        root.add("_meta", context.meta(name(), entries));
        root.add("blocks", blocks);
        write(context, root);
        return entries;
    }

    /**
     * Renders a distinct-shape map as the JSON array {@code shapes.json} stores.
     *
     * @param distinctShapes by canonical key, in first-seen order.
     * @return one JSON object per distinct geometry, each carrying its key.
     */
    private static JsonArray shapes(Map<String, BlockShapeData> distinctShapes) {
        JsonArray array = new JsonArray();
        for (Map.Entry<String, BlockShapeData> entry : distinctShapes.entrySet()) {
            JsonObject json = entry.getValue().toJson();
            json.addProperty("key", entry.getKey());
            array.add(json);
        }
        return array;
    }

    /**
     * Converts a voxel shape into its rounded box list.
     *
     * @param shape geometry to convert; {@code null} and empty shapes both describe as empty.
     * @return the shape as boxes, each coordinate rounded to {@link MathUtil#PRECISION} digits.
     */
    public static BlockShapeData describe(VoxelShape shape) {
        if (shape == null || shape.isEmpty()) {
            return new BlockShapeData(true, List.of());
        }
        List<BlockShapeData.Box> boxes = new java.util.ArrayList<>();
        for (AABB box : shape.toAabbs()) {
            boxes.add(new BlockShapeData.Box(
                    MathUtil.round(box.minX), MathUtil.round(box.minY), MathUtil.round(box.minZ),
                    MathUtil.round(box.maxX), MathUtil.round(box.maxY), MathUtil.round(box.maxZ)));
        }
        return new BlockShapeData(boxes.isEmpty(), List.copyOf(boxes));
    }

    /**
     * Canonical identity of a shape; identical geometries collapse into one entry.
     *
     * @param shape shape to identify.
     * @return a key that is equal for equal geometries, usable as a JSON object key.
     */
    public static String key(BlockShapeData shape) {
        if (shape.empty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder();
        for (BlockShapeData.Box box : shape.boxes()) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        }
        return builder.toString();
    }
}
