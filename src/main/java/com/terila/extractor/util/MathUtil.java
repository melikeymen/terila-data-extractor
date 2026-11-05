package com.terila.extractor.util;

// Written by AI
import com.terila.extractor.core.ExtractorConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeric helpers.
 *
 * <p>Floats are rounded before they are written so that repeated runs produce byte-identical JSON
 * despite floating point noise. The number of digits is one value, defined once on
 * {@link ExtractorConfig#DEFAULT_FLOAT_PRECISION} and honoured by a run's
 * {@link ExtractorConfig#floatPrecision()}; the callers that have a context always pass that value
 * through, and the no-argument overloads fall back to the same default so the two can never drift.
 */
public final class MathUtil {

    /** Digits kept for extracted floats when no run configuration is available. */
    public static final int PRECISION = ExtractorConfig.DEFAULT_FLOAT_PRECISION;

    private MathUtil() {
    }

    /** Rounds a double to the project-wide default precision. */
    public static double round(double value) {
        return round(value, PRECISION);
    }

    /**
     * Rounds a double to {@code digits} decimal places.
     *
     * <p>Non-finite values are returned unchanged: JSON already represents them as strings and
     * rounding NaN or infinity would only throw.
     */
    public static double round(double value, int digits) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    /** Rounds a float to the default precision and narrows the result back to float. */
    public static float round(float value) {
        return (float) round((double) value);
    }

    /** Float variant of {@link #round(double, int)}; used when the rounded value stays a float. */
    public static float round(float value, int digits) {
        return (float) round((double) value, digits);
    }

    /** Clamps an int into the inclusive {@code [min, max]} range. */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Clamps a double into the inclusive {@code [min, max]} range. */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Ceiling division for non-negative operands. */
    public static int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
