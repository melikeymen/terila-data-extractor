package com.terila.extractor.core;

// Written by AI
import java.util.Locale;

/**
 * Selects how NBT data is emitted.
 *
 * <p>{@link #WIRE} is the compatibility default: direct packet payloads stay exactly where they
 * were before this option existed. {@link #RAW} writes one unframed NBT tag per logical entry plus
 * a manifest, and {@link #BOTH} writes both representations.
 */
public enum NbtFormat {

    /** Direct wire payloads: {@code registries/nbt}, {@code tags/all.nbt}, ... */
    WIRE,

    /** Per-entry raw NBT tags plus manifests under the raw output tree. */
    RAW,

    /** Both the wire payloads and the per-entry raw tree. */
    BOTH;

    /** Whether this mode writes the existing packet-shaped NBT files. */
    public boolean writesWire() {
        return this == WIRE || this == BOTH;
    }

    /** Whether this mode writes per-entry raw NBT files and manifests. */
    public boolean writesRaw() {
        return this == RAW || this == BOTH;
    }

    /**
     * Parses the command-line spelling.
     *
     * @param value {@code wire}, {@code raw} or {@code both}; case insensitive
     * @return the matching mode
     * @throws IllegalArgumentException when the value is not one of the three names
     */
    public static NbtFormat parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--nbt-format requires wire, raw or both");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "--nbt-format must be wire, raw or both: " + value);
        }
    }
}
