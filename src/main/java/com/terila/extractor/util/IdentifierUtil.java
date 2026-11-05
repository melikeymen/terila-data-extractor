package com.terila.extractor.util;

// Written by AI
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Identifier helpers.
 *
 * <p>Note: Minecraft 26.x renamed {@code ResourceLocation} to {@link Identifier}; this class keeps
 * the parsing quirks in one place.
 */
public final class IdentifierUtil {

    public static final String DEFAULT_NAMESPACE = "minecraft";

    private IdentifierUtil() {
    }

    /** Creates an identifier from an explicit namespace and path. */
    public static Identifier of(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    /** Creates a {@code minecraft:} identifier from a bare path. */
    public static Identifier minecraft(String path) {
        return Identifier.fromNamespaceAndPath(DEFAULT_NAMESPACE, path);
    }

    /** Parses {@code namespace:path}; throws on malformed input, matching the game's parser. */
    public static Identifier parse(String value) {
        return Identifier.parse(value);
    }

    /** Parses an identifier, returning empty instead of throwing for malformed input. */
    public static Optional<Identifier> tryParse(String value) {
        if (StringUtil.isNullOrEmpty(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Identifier.parse(value));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** Whether {@code value} is a syntactically valid identifier. */
    public static boolean isValid(String value) {
        return tryParse(value).isPresent();
    }

    /** {@code minecraft:stone} becomes {@code minecraft_stone}, for per-entry file names. */
    public static String toFileName(Identifier identifier) {
        return StringUtil.toFileName(identifier.toString());
    }

    /** Namespace part of an identifier. */
    public static String namespace(Identifier identifier) {
        return identifier.getNamespace();
    }

    /** Path part of an identifier, without the namespace. */
    public static String path(Identifier identifier) {
        return identifier.getPath();
    }

    /** Strips a leading {@code #} used to mark tag references. */
    public static String stripTagPrefix(String value) {
        return value.startsWith("#") ? value.substring(1) : value;
    }

    /** Whether a reference points at a tag rather than a concrete entry. */
    public static boolean isTagReference(String value) {
        return value != null && value.startsWith("#");
    }
}
