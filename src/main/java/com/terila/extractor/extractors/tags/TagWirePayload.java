package com.terila.extractor.extractors.tags;

// Written by AI
import com.terila.extractor.util.WireFormat;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Byte-level helpers for the {@code update_tags} (0x0E) payload.
 *
 * <p>The payload the game sends is {@code registry_count VarInt}, then for each registry its id,
 * a {@code tag_count VarInt} and that many {@code tag id + entry_count + protocol ids} blocks. The
 * game's own {@link ClientboundUpdateTagsPacket} codec is the only writer of the full payload; the
 * other methods here split a payload the codec produced and recompose a subset of it, so a copied
 * registry block is guaranteed to be the exact bytes the game wrote rather than a re-encoding.
 *
 * <p>This is what lets {@code tags/client_tags.bin} hold only the registries a vanilla client
 * expects while staying byte-identical to {@code tags/all.nbt} for every registry it keeps.
 */
public final class TagWirePayload {

    private TagWirePayload() {
    }

    /**
     * Encodes a complete Update Tags payload through the game's own stream codec.
     *
     * <p>Unlike {@link TagNetworkSerialization#serializeTagsToNetwork}, this keeps registries whose
     * tags are all empty: vanilla serialises a registry as long as it declares at least one tag, and
     * the client rejects a payload that omits a registry it expects. An entry mapped to
     * {@link TagNetworkSerialization.NetworkPayload#EMPTY} is encoded as {@code tag_count = 0}.
     *
     * @param payloads registries to write, keyed by registry id.
     * @return the exact bytes a {@code write_packet(out, 0x0E, payload)} call would send.
     */
    public static byte[] encode(
            Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> payloads) {
        ClientboundUpdateTagsPacket packet = new ClientboundUpdateTagsPacket(payloads);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundUpdateTagsPacket.STREAM_CODEC.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    /**
     * Splits an encoded payload into one byte block per registry.
     *
     * <p>A block starts at that registry's {@code tag_count VarInt} — the registry id string is not
     * part of it — and ends where the next registry starts. The blocks are copies, so a consumer can
     * concatenate them without touching the source array.
     *
     * @param payload a complete Update Tags payload.
     * @return registry id to its {@code [tag_count][tags...]} block, in payload order.
     * @throws IOException when the payload is truncated or a registry appears twice.
     */
    public static Map<String, byte[]> blocks(byte[] payload) throws IOException {
        Map<String, byte[]> blocks = new LinkedHashMap<>();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            int registryCount = buffer.readVarInt();
            for (int registry = 0; registry < registryCount; registry++) {
                String registryId = buffer.readUtf();
                int start = buffer.readerIndex();
                int tagCount = buffer.readVarInt();
                for (int tag = 0; tag < tagCount; tag++) {
                    buffer.readUtf();
                    int entryCount = buffer.readVarInt();
                    for (int entry = 0; entry < entryCount; entry++) {
                        buffer.readVarInt();
                    }
                }
                int end = buffer.readerIndex();
                byte[] block = new byte[end - start];
                System.arraycopy(payload, start, block, 0, block.length);
                if (blocks.put(registryId, block) != null) {
                    throw new IOException("duplicate registry in update tags payload: " + registryId);
                }
            }
            if (buffer.readableBytes() != 0) {
                throw new IOException("trailing bytes after update tags payload: "
                        + buffer.readableBytes());
            }
            return blocks;
        } finally {
            buffer.release();
        }
    }

    /**
     * Reads the registry ids of an encoded payload in their on-wire order.
     *
     * @param payload a complete Update Tags payload.
     * @return the registry ids, in payload order.
     * @throws IOException when the payload is truncated or has trailing bytes.
     */
    public static List<String> registryIds(byte[] payload) throws IOException {
        List<String> ids = new ArrayList<>();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            int registryCount = buffer.readVarInt();
            for (int registry = 0; registry < registryCount; registry++) {
                ids.add(buffer.readUtf());
                int tagCount = buffer.readVarInt();
                for (int tag = 0; tag < tagCount; tag++) {
                    buffer.readUtf();
                    int entryCount = buffer.readVarInt();
                    for (int entry = 0; entry < entryCount; entry++) {
                        buffer.readVarInt();
                    }
                }
            }
            if (buffer.readableBytes() != 0) {
                throw new IOException("trailing bytes after update tags payload: "
                        + buffer.readableBytes());
            }
            return ids;
        } finally {
            buffer.release();
        }
    }

    /**
     * Builds a payload that contains exactly the listed registries, in that order.
     *
     * <p>Each block is copied verbatim from {@code blocks}; a registry the source payload does not
     * have is written with {@code tag_count = 0} rather than being skipped, so the whitelist decides
     * the registry set and nothing else does.
     *
     * @param registries registry ids to write, in output order.
     * @param blocks     registry id to {@code [tag_count][tags...]} block.
     * @return the composed payload, without a packet id or length prefix.
     * @throws IOException when the payload cannot be written.
     */
    public static byte[] compose(List<String> registries, Map<String, byte[]> blocks)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            WireFormat.writeVarInt(output, registries.size());
            for (String registry : registries) {
                WireFormat.writeString(output, registry);
                byte[] block = blocks.get(registry);
                if (block == null) {
                    WireFormat.writeVarInt(output, 0);
                } else {
                    output.write(block);
                }
            }
        }
        return bytes.toByteArray();
    }
}
