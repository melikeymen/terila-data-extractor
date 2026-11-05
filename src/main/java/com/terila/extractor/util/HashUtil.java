package com.terila.extractor.util;

// Written by AI
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 helpers backing the determinism guarantee. */
public final class HashUtil {

    private HashUtil() {
    }

    /** Hashes UTF-8 text and prefixes the result with {@code sha256:}. */
    public static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Hashes raw bytes and prefixes the result with {@code sha256:}. */
    public static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Hashes a file's bytes; I/O failures surface as an unchecked exception. */
    public static String sha256File(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash " + file, e);
        }
    }

    /**
     * Order-independent hash of a directory tree: every file contributes its relative path and its
     * content, so two runs producing the same data hash identically.
     */
    public static String sha256Tree(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = com.terila.extractor.core.FileManager.listFiles(root);
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed to hash tree " + root, e);
        }
    }
}
