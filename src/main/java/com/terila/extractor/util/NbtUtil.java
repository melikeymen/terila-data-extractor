package com.terila.extractor.util;

// Written by AI
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * NBT helpers.
 *
 * <p>Conversion goes through the datafixerupper dynamic ops so every tag type (including the
 * array-shaped ones) round-trips exactly the way the game itself serialises them.
 */
public final class NbtUtil {

    private NbtUtil() {
    }

    /** Converts any NBT tag to its JSON representation. */
    public static JsonElement toJson(Tag tag) {
        return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
    }

    /** Converts a JSON document back to NBT. */
    public static Tag fromJson(JsonElement json) {
        return JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
    }

    /** Renders a compound in the game's SNBT notation, useful in diagnostics. */
    public static String toSnbt(CompoundTag tag) {
        return tag.toString();
    }

    /** Compact, single-line representation used when an embedded structure is too large. */
    public static String summarise(Tag tag) {
        if (tag == null) {
            return null;
        }
        String snbt = tag.toString();
        return snbt.length() <= 512 ? snbt : snbt.substring(0, 509) + "...";
    }

    /** Whether a tag is a compound (named members). */
    public static boolean isCompound(Tag tag) {
        return tag instanceof CompoundTag;
    }

    /** Whether a tag is a list (ordered values of one element type). */
    public static boolean isList(Tag tag) {
        return tag instanceof ListTag;
    }

    /** Elements of a list tag; an empty list for every other tag type. */
    public static List<Tag> listElements(Tag tag) {
        if (tag instanceof ListTag list) {
            return list.stream().map(element -> (Tag) element).toList();
        }
        return List.of();
    }
}
