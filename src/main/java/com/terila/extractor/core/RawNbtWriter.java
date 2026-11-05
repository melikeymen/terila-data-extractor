package com.terila.extractor.core;

// Written by AI
import com.terila.extractor.util.HashUtil;
import com.terila.extractor.util.StringUtil;
import com.terila.extractor.util.WireFormat;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Writes one unnamed NBT tag as a standalone raw file.
 *
 * <p>Raw files deliberately contain no length prefix, compression header or compound envelope:
 * the first byte is the NBT type byte. A compound therefore starts with {@code 0A}; that is the
 * tag's own root, not a wrapper around the file. The wire mode uses the packet-shaped writers
 * instead.
 */
public final class RawNbtWriter {

    private RawNbtWriter() {
    }

    /**
     * Writes a canonical unnamed NBT tag to a path relative to the output root.
     *
     * @param context      current extraction context
     * @param relativePath output-relative file path
     * @param tag          tag to write
     * @return the absolute file that was written
     * @throws IOException when the target cannot be written
     */
    public static Path writeNbt(ExtractorContext context, String relativePath, Tag tag)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            WireFormat.writeNbt(output, tag);
        }
        Path target = context.writer().resolve(relativePath);
        Files.createDirectories(target.getParent());
        AtomicFileWriter.writeBytes(target, bytes.toByteArray());
        return target;
    }

    /**
     * Chooses a deterministic, filesystem-safe file name for an identifier.
     *
     * <p>The normal form is {@code minecraft_badlands.nbt}. The short hash suffix only appears when
     * two identifiers would flatten onto the same name, which keeps custom packs safe on Windows
     * and other filesystems without changing vanilla names.
     *
     * @param id   full identifier, e.g. {@code minecraft:badlands}
     * @param used names already handed out for the current manifest
     * @return a unique {@code .nbt} file name
     */
    public static String uniqueFileName(String id, Set<String> used) {
        String base = StringUtil.toFileName(id);
        String candidate = base + ".nbt";
        if (used.add(candidate)) {
            return candidate;
        }

        String digest = HashUtil.sha256(id);
        String suffix = digest.substring("sha256:".length(), "sha256:".length() + 8);
        candidate = base + "-" + suffix + ".nbt";
        if (!used.add(candidate)) {
            throw new IllegalStateException("Raw NBT file name collision for " + id);
        }
        return candidate;
    }
}
