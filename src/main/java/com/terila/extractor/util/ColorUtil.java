package com.terila.extractor.util;

// Written by AI
/** Colour conversions shared by the biome/effects and text extractors. */
public final class ColorUtil {

    private ColorUtil() {
    }

    /** {@code 0xRRGGBB} integer to {@code "#rrggbb"}. */
    public static String toHex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    /** {@code 0xAARRGGBB} integer to {@code "#rrggbbaa"}. */
    public static String toHexWithAlpha(int argb) {
        return String.format("#%08x", argb);
    }

    /** Red channel of a packed {@code 0xRRGGBB} colour. */
    public static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    /** Green channel of a packed {@code 0xRRGGBB} colour. */
    public static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    /** Blue channel of a packed {@code 0xRRGGBB} colour. */
    public static int blue(int rgb) {
        return rgb & 0xFF;
    }

    /** Alpha channel of a packed {@code 0xAARRGGBB} colour. */
    public static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** Packs red, green and blue channels into {@code 0xRRGGBB}. */
    public static int fromRgb(int red, int green, int blue) {
        return ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    /** Packs alpha, red, green and blue channels into {@code 0xAARRGGBB}. */
    public static int fromArgb(int alpha, int red, int green, int blue) {
        return ((alpha & 0xFF) << 24) | fromRgb(red, green, blue);
    }
}
