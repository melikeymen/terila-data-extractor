package com.terila.extractor.extractors.blocks;

// Written by AI
import com.terila.extractor.TestSupport;
import com.terila.extractor.extractors.blocks.model.BlockShapeData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the shape geometry conversion.
 *
 * <p>Collision and outline are different questions and used to be conflated: a button has no collision
 * box at all but certainly has an outline, so a document that only records collision describes it as
 * "empty" and a renderer cannot draw its selection box.
 */
class BlockShapeExtractorTest {

    @BeforeAll
    static void bootStrap() {
        TestSupport.bootStrap();
    }

    /** The case the collision-only extraction got wrong. */
    @Test
    void aButtonHasAnOutlineWithoutCollision() {
        BlockState button = Blocks.ACACIA_BUTTON.defaultBlockState();

        BlockShapeData collision = collision(button);
        BlockShapeData outline = outline(button);

        assertTrue(collision.empty(), "a button does not collide");
        assertEquals(0, collision.boxes().size());
        assertFalse(outline.empty(), "a button still has an outline");
        assertTrue(outline.boxes().size() >= 1, "outline boxes: " + outline.boxes());
        assertNotEquals(BlockShapeExtractor.key(collision), BlockShapeExtractor.key(outline));
    }

    /** A block that collides fully needs both shapes to describe the same cube. */
    @Test
    void aFullBlockHasTheSameCollisionAndOutline() {
        BlockState stone = Blocks.STONE.defaultBlockState();

        BlockShapeData collision = collision(stone);
        assertEquals(1, collision.boxes().size());
        assertEquals(List.of(new BlockShapeData.Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)), collision.boxes());
        assertEquals(BlockShapeExtractor.key(collision), BlockShapeExtractor.key(outline(stone)));
    }

    /** Equal geometry must collapse onto one key so the document can store it once. */
    @Test
    void equalGeometrySharesAKey() {
        BlockShapeData one = new BlockShapeData(false,
                List.of(new BlockShapeData.Box(0.0, 0.0, 0.0, 0.5, 1.0, 0.5)));
        BlockShapeData same = new BlockShapeData(false,
                List.of(new BlockShapeData.Box(0.0, 0.0, 0.0, 0.5, 1.0, 0.5)));

        assertEquals(BlockShapeExtractor.key(one), BlockShapeExtractor.key(same));
        assertNotEquals("empty", BlockShapeExtractor.key(one));
    }

    /** An air state is empty, and the empty key is the documented sentinel. */
    @Test
    void anEmptyShapeUsesTheEmptyKey() {
        BlockShapeData empty = BlockShapeExtractor.describe(
                Blocks.AIR.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));

        assertTrue(empty.empty());
        assertEquals("empty", BlockShapeExtractor.key(empty));
        assertEquals("empty", BlockShapeExtractor.key(BlockShapeExtractor.describe(null)));
    }

    private static BlockShapeData collision(BlockState state) {
        return BlockShapeExtractor.describe(
                state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    private static BlockShapeData outline(BlockState state) {
        return BlockShapeExtractor.describe(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }
}
