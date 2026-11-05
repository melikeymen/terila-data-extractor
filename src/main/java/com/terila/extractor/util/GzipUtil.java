package com.terila.extractor.util;

// Written by AI
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Gzip helpers for the optional compressed output mode. */
public final class GzipUtil {

    private GzipUtil() {
    }

    /** Gzip-compresses a byte array. */
    public static byte[] compress(byte[] raw) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(raw);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to gzip payload", e);
        }
    }

    /** Decompresses a gzip payload. */
    public static byte[] decompress(byte[] compressed) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to gunzip payload", e);
        }
    }

    /** Compresses text and returns the raw bytes as an ISO-8859-1 string for transport. */
    public static String compressToString(String text) {
        return new String(compress(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.ISO_8859_1);
    }
}
