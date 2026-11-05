package com.terila.extractor.core;

// Written by AI
import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Set;

/**
 * Minimal structured logger.
 *
 * <p>Deliberately dependency free: the extractor must run both inside Fabric (where SLF4J exists)
 * and inside the standalone bootstrap (where it does not), so it writes to {@link System#out}
 * / {@link System#err} directly.
 */
public final class ExtractorLogger {

    public enum Level { TRACE, DEBUG, INFO, WARN, ERROR }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Levels the default configuration prints. */
    private static final Set<Level> DEFAULT_LEVELS = EnumSet.of(Level.INFO, Level.WARN, Level.ERROR);

    /**
     * Levels currently printed.
     *
     * <p>{@code volatile} and replaced wholesale rather than mutated: extraction logs from a thread
     * pool, so a reader must never observe a half-updated set.
     */
    private static volatile Set<Level> enabledLevels = DEFAULT_LEVELS;

    private final String scope;

    private ExtractorLogger(String scope) {
        this.scope = scope;
    }

    /** Logger scoped to a class's simple name. */
    public static ExtractorLogger of(Class<?> owner) {
        return new ExtractorLogger(owner.getSimpleName());
    }

    /** Logger scoped to an arbitrary label. */
    public static ExtractorLogger scoped(String scope) {
        return new ExtractorLogger(scope);
    }

    /** Enables every level when {@code verbose}, otherwise the default set. */
    public static void setVerbose(boolean verbose) {
        enabledLevels = verbose ? EnumSet.allOf(Level.class) : DEFAULT_LEVELS;
    }

    /** Whether a level is currently printed; lets callers skip building an expensive message. */
    public static boolean isEnabled(Level level) {
        return enabledLevels.contains(level);
    }

    /** A logger whose scope is nested under this one, e.g. {@code Extractor/blocks}. */
    public ExtractorLogger child(String childScope) {
        return new ExtractorLogger(scope + "/" + childScope);
    }

    /** Logs a trace message; formatting only happens when the level is enabled. */
    public void trace(String message, Object... args) {
        log(Level.TRACE, message, args);
    }

    /** Logs a debug message; formatting only happens when the level is enabled. */
    public void debug(String message, Object... args) {
        log(Level.DEBUG, message, args);
    }

    /** Logs an informational message. */
    public void info(String message, Object... args) {
        log(Level.INFO, message, args);
    }

    /** Logs a warning. */
    public void warn(String message, Object... args) {
        log(Level.WARN, message, args);
    }

    /** Logs an error without an attached throwable. */
    public void error(String message, Object... args) {
        log(Level.ERROR, message, args);
    }

    /** Logs a message and the throwable's stack trace, both subject to the error level. */
    public void error(String message, Throwable throwable) {
        if (!isEnabled(Level.ERROR)) {
            return;
        }
        log(Level.ERROR, message);
        throwable.printStackTrace(err());
    }

    /**
     * Formats and prints one line.
     *
     * <p>The enablement check comes first: a disabled level must not even build its message, because
     * some call sites format registry dumps into the arguments and the debug path would then cost as
     * much as the extraction itself.
     */
    private void log(Level level, String message, Object... args) {
        if (!isEnabled(level)) {
            return;
        }
        String rendered = args.length == 0 ? message : String.format(message, args);
        PrintStream out = level.ordinal() >= Level.WARN.ordinal() ? err() : System.out;
        out.printf("[%s] %-5s %s - %s%n", LocalTime.now().format(TIME), level, scope, rendered);
    }

    private static PrintStream err() {
        return System.err;
    }
}
