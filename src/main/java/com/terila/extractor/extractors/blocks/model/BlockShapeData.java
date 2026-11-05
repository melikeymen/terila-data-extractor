package com.terila.extractor.extractors.blocks.model;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Collision geometry of a block state, as a list of axis-aligned boxes.
 *
 * @param empty whether the shape is empty (no collision).
 * @param boxes the boxes that make up the shape.
 */
public record BlockShapeData(boolean empty, List<Box> boxes) {

    /**
     * One collision box in block-local coordinates.
     *
     * @param minX lower X bound, relative to the block's origin.
     * @param minY lower Y bound.
     * @param minZ lower Z bound.
     * @param maxX upper X bound.
     * @param maxY upper Y bound.
     * @param maxZ upper Z bound.
     */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        /** The bounds as {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
        public double[] toArray() {
            return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
        }

        /** The bounds as a six element JSON array, in the same order as {@link #toArray()}. */
        public JsonArray toJson() {
            JsonArray array = new JsonArray();
            array.add(minX);
            array.add(minY);
            array.add(minZ);
            array.add(maxX);
            array.add(maxY);
            array.add(maxZ);
            return array;
        }
    }

    /** {@code {empty, box_count, boxes}} as written to {@code blocks/shapes.json}. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("empty", empty);
        json.addProperty("box_count", boxes.size());
        JsonArray array = new JsonArray();
        boxes.forEach(box -> array.add(box.toJson()));
        json.add("boxes", array);
        return json;
    }
}
