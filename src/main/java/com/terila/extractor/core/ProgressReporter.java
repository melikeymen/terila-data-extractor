package com.terila.extractor.core;

// Written by AI
/**
 * Tracks and reports progress of a run.
 *
 * <p>Thread-safe: extractors report from pool threads.
 */
public final class ProgressReporter {

    private final ExtractorLogger log;
    private final int total;
    private int completed;

    /** Creates a reporter for {@code total} extractors, writing progress to {@code log}. */
    public ProgressReporter(ExtractorLogger log, int total) {
        this.log = log;
        this.total = total;
    }

    /** Marks an extractor as started without advancing the completed counter. */
    public synchronized void started(String name) {
        log.debug("starting %s (%d/%d)", name, completed, total);
    }

    /** Records a successful completion and logs its entry count and duration. */
    public synchronized void completed(String name, int entries, long durationMillis) {
        completed++;
        log.info("[%d/%d] %s -> %d entries in %d ms", completed, total, name, entries, durationMillis);
    }

    /** Records a failed extractor and logs its stack trace. */
    public synchronized void failed(String name, Throwable cause) {
        completed++;
        log.error(String.format("[%d/%d] %s FAILED: %s", completed, total, name, cause), cause);
    }

    /** Number of finished extractors, successful or not. */
    public synchronized int completed() {
        return completed;
    }

    /** Total number of extractors this reporter expects. */
    public int total() {
        return total;
    }
}
