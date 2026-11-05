package com.terila.extractor;

// Written by AI
import com.terila.extractor.util.WireFormat;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.RegistrySynchronization.PackedRegistryEntry;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decoders shared by the wire-format tests. */
final class WireFormatTestSupport {

    private WireFormatTestSupport() {
    }

    record RegistryWire(String registryId, List<PackedRegistryEntry> entries) {
    }

    record TagsWire(Map<ResourceKey<? extends net.minecraft.core.Registry<?>>,
            TagNetworkSerialization.NetworkPayload> tags) {
    }

    record Entry(String id, Tag nbt) {
    }

    record IdNbtWire(int count, List<Entry> entries) {
    }

    record RawTag(String id, List<Integer> ids) {
    }

    record RawTagRegistry(String id, List<RawTag> tags) {
    }

    static RegistryWire readRegistry(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            ClientboundRegistryDataPacket packet =
                    ClientboundRegistryDataPacket.STREAM_CODEC.decode(buffer);
            if (buffer.readableBytes() != 0) {
                throw new IOException("trailing bytes after registry payload: " + file);
            }
            return new RegistryWire(packet.registry().identifier().toString(), packet.entries());
        } finally {
            buffer.release();
        }
    }

    static TagsWire readTags(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            ClientboundUpdateTagsPacket packet =
                    ClientboundUpdateTagsPacket.STREAM_CODEC.decode(buffer);
            if (buffer.readableBytes() != 0) {
                throw new IOException("trailing bytes after tags payload: " + file);
            }
            return new TagsWire(packet.tags());
        } finally {
            buffer.release();
        }
    }

    /** Reads the tag wire directly, preserving its on-disk order for the ordering assertions. */
    static List<RawTagRegistry> readTagsOrder(Path file) throws IOException {
        return readTagsOrder(Files.readAllBytes(file), file.toString());
    }

    /** Reads a tag payload already in memory, preserving its order. */
    static List<RawTagRegistry> readTagsOrder(byte[] payload, String source) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int registryCount = WireFormat.readVarInt(input);
            List<RawTagRegistry> registries = new ArrayList<>();
            for (int r = 0; r < registryCount; r++) {
                String registryId = WireFormat.readString(input);
                int tagCount = WireFormat.readVarInt(input);
                List<RawTag> tags = new ArrayList<>();
                for (int t = 0; t < tagCount; t++) {
                    String tagId = WireFormat.readString(input);
                    int entryCount = WireFormat.readVarInt(input);
                    List<Integer> ids = new ArrayList<>();
                    for (int i = 0; i < entryCount; i++) {
                        ids.add(WireFormat.readVarInt(input));
                    }
                    tags.add(new RawTag(tagId, ids));
                }
                registries.add(new RawTagRegistry(registryId, tags));
            }
            if (input.read() != -1) {
                throw new IOException("trailing bytes after tags wire: " + source);
            }
            return registries;
        }
    }

    /**
     * Reads a hex capture of an Update Tags payload.
     *
     * <p>Whitespace, commas and {@code 0x} prefixes are ignored so a packet dump can be saved as
     * either a single line or a formatted listing. A leading VarInt packet id ({@code 0x0E}) is
     * dropped when the rest of the capture only parses without it, because a sniffer capture often
     * includes the packet header.
     *
     * @param file hex text file.
     * @return the payload bytes, without a packet id or length prefix.
     * @throws IOException when the file is not valid hex or is too short to be a payload.
     */
    static byte[] readHexPayload(Path file) throws IOException {
        String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder digits = new StringBuilder();
        for (String token : text.split("[^0-9A-Fa-f]+")) {
            digits.append(token);
        }
        if (digits.length() == 0 || digits.length() % 2 != 0) {
            throw new IOException("not a whole number of bytes in hex capture: " + file);
        }
        byte[] bytes = new byte[digits.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(
                    digits.substring(index * 2, index * 2 + 2), 16);
        }

        try {
            readTagsOrder(bytes, file.toString());
            return bytes;
        } catch (IOException withoutHeader) {
            // A capture that starts with the packet id has one VarInt byte in front of the payload.
            if (bytes.length > 1 && bytes[0] == 0x0E) {
                byte[] payload = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
                readTagsOrder(payload, file.toString());
                return payload;
            }
            throw withoutHeader;
        }
    }

    static int tagsEntryCount(TagsWire wire) {
        int total = 0;
        for (TagNetworkSerialization.NetworkPayload payload : wire.tags().values()) {
            for (IntList ids : payload.tags().values()) {
                total += ids.size();
            }
        }
        return total;
    }

    static int tagsCount(TagsWire wire) {
        return wire.tags().values().stream()
                .mapToInt(TagNetworkSerialization.NetworkPayload::size)
                .sum();
    }

    static IdNbtWire readIdNbt(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            int count = WireFormat.readVarInt(input);
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = WireFormat.readString(input);
                Tag nbt = NbtIo.readAnyTag(input, NbtAccounter.unlimitedHeap());
                entries.add(new Entry(id, nbt));
            }
            if (input.read() != -1) {
                throw new IOException("trailing bytes after counted NBT: " + file);
            }
            return new IdNbtWire(count, entries);
        }
    }

    static Tag readFramedNbt(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int length = WireFormat.readVarInt(input);
            byte[] payload = new byte[length];
            input.readFully(payload);
            if (input.read() != -1) {
                throw new IOException("trailing bytes after framed NBT: " + file);
            }
            return NbtIo.readAnyTag(new DataInputStream(new ByteArrayInputStream(payload)),
                    NbtAccounter.unlimitedHeap());
        }
    }

    static Map<String, Tag> idNbtById(Path file) throws IOException {
        Map<String, Tag> result = new LinkedHashMap<>();
        for (Entry entry : readIdNbt(file).entries()) {
            if (result.put(entry.id(), entry.nbt()) != null) {
                throw new IOException("duplicate id in " + file + ": " + entry.id());
            }
        }
        return result;
    }
}
