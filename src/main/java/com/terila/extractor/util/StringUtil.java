package com.terila.extractor.util;

// Written by AI
import java.util.Locale;

/** String helpers used across extractors. */
public final class StringUtil {

    private StringUtil() {
    }

    /** Whether a string is {@code null} or has zero length. */
    public static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /** Returns the input unchanged, or an empty string when it is {@code null}. */
    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** {@code minecraft:stone} -> {@code stone}; leaves non-namespaced input untouched. */
    public static String path(String identifier) {
        int colon = identifier.indexOf(':');
        return colon < 0 ? identifier : identifier.substring(colon + 1);
    }

    /** {@code minecraft:stone} -> {@code minecraft}; defaults to {@code minecraft}. */
    public static String namespace(String identifier) {
        int colon = identifier.indexOf(':');
        return colon < 0 ? "minecraft" : identifier.substring(0, colon);
    }

    /** {@code TextComponent} memory addresses / lambdas are stripped from display names. */
    public static String sanitiseName(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    /** Turns an enum or identifier into the snake_case form used in the JSON output. */
    public static String toSnakeCase(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append(c);
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    /** Replaces the filename-hostile characters of an identifier, e.g. for {@code minecraft:stone}. */
    public static String toFileName(String identifier) {
        return identifier.replace(':', '_').replace('/', '_');
    }

    /** Returns {@code spaces} spaces; negative counts become zero. */
    public static String indent(int spaces) {
        return " ".repeat(Math.max(0, spaces));
    }
}
