package com.terila.extractor.util;

// Written by AI
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread helpers for the extraction pool. */
public final class ThreadUtil {

    private ThreadUtil() {
    }

    /** A named, daemon thread factory so the tool can exit even if a task is stuck. */
    public static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Number of worker threads to use for a given amount of independent work. */
    public static int suggestedThreadCount(int workItems) {
        int cpus = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(Math.min(cpus, 8), Math.max(1, workItems)));
    }
}
