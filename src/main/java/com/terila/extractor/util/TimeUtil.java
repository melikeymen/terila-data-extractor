package com.terila.extractor.util;

// Written by AI
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Timestamp formatting for the generated metadata. */
public final class TimeUtil {

    /** Stable, ISO-8601 UTC timestamp used inside every {@code _meta} block. */
    public static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private TimeUtil() {
    }

    /** Current time formatted with {@link #ISO_UTC}. */
    public static String now() {
        return ISO_UTC.format(Instant.now());
    }

    /** Formats an instant with the stable metadata timestamp format. */
    public static String format(Instant instant) {
        return ISO_UTC.format(instant);
    }

    /** Formats a duration as milliseconds or seconds, for progress logs. */
    public static String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format("%.2f s", millis / 1000.0);
    }

    /** Measures a block of work and returns its duration in milliseconds. */
    public static long measure(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
