package com.terila.extractor.util;

// Written by AI
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

/**
 * Primitive writers for the raw payloads Terila's C++ server consumes.
 *
 * <p>These are the encodings {@code FriendlyByteBuf} uses: VarInt lengths, UTF-8 strings, and
 * unnamed NBT tags. The extractor deliberately uses them only where the game does not expose a
 * {@code StreamCodec}; packet payloads themselves are encoded through the game's own codec.
 */
public final class WireFormat {

    private WireFormat() {
    }

    /** Writes a VarInt exactly as {@code FriendlyByteBuf.writeVarInt} does. */
    public static void writeVarInt(DataOutput output, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    /** Reads a VarInt exactly as {@code FriendlyByteBuf.readVarInt} does. */
    public static int readVarInt(DataInput input) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            byte current = input.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt is too big");
            }
        }
    }

    /** Writes a UTF-8 string with its VarInt byte length. */
    public static void writeString(DataOutput output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    /** Reads a UTF-8 string with its VarInt byte length. */
    public static String readString(DataInput input) throws IOException {
        int length = readVarInt(input);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes one unnamed NBT tag, the form used inside network packets.
     *
     * <p>{@code NbtIo.writeAnyTag} writes the type byte followed by the tag payload. A compound
     * therefore starts with {@code 0A}, but that is the content of an entry, not a wrapper around
     * the wire payload.
     */
    public static void writeNbt(DataOutput output, Tag tag) throws IOException {
        NbtIo.writeAnyTag(canonicalNbt(tag), output);
    }

    /**
     * Returns a deep copy of an NBT tag with every compound's keys sorted.
     *
     * <p>NBT compounds are unordered, but their encoded key order still affects the bytes. Several
     * codecs serialise identity-keyed maps (notably advancement criteria), whose iteration order can
     * vary between JVMs. Sorting compound keys recursively makes the payload stable without changing
     * list order, which is semantic.
     */
    public static Tag canonicalNbt(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            CompoundTag sorted = new CompoundTag();
            for (String key : new TreeSet<>(compound.keySet())) {
                sorted.put(key, canonicalNbt(compound.get(key)));
            }
            return sorted;
        }
        if (tag instanceof ListTag list) {
            ListTag sorted = new ListTag();
            for (int index = 0; index < list.size(); index++) {
                sorted.add(canonicalNbt(list.get(index)));
            }
            return sorted;
        }
        return tag;
    }
}
