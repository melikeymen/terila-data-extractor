package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe sink for every problem raised during a run.
 *
 * <p>Extractors are executed in parallel, so all mutation goes through synchronized methods. The
 * collector never throws: raising an issue must not be able to abort extraction.
 *
 * <p>The number of retained issues is bounded by {@link ExtractorConfig#DEFAULT_MAX_ISSUES}; a run
 * that exceeds it records one extra warning saying so, which keeps the report readable while still
 * making the overflow visible.
 */
public final class ErrorCollector {

    private final List<ExtractionIssue> issues = Collections.synchronizedList(new ArrayList<>());
    private final int maxIssues;

    /** Creates a collector with the default issue limit. */
    public ErrorCollector() {
        this(ExtractorConfig.DEFAULT_MAX_ISSUES);
    }

    /** Creates a collector that retains at most {@code maxIssues} entries. */
    public ErrorCollector(int maxIssues) {
        this.maxIssues = maxIssues;
    }

    /** Records one issue, or a suppression marker once the retention limit is reached. */
    public void add(ExtractionIssue issue) {
        synchronized (issues) {
            if (issues.size() < maxIssues) {
                issues.add(issue);
            } else if (issues.size() == maxIssues) {
                issues.add(ExtractionIssue.warning(
                        "ISSUE_LIMIT_REACHED", "ErrorCollector", null,
                        "Further issues suppressed after " + maxIssues + " entries"));
            }
        }
    }

    /** Records an error at a document location. */
    public void error(String type, String extractor, String entry, String field, String message) {
        add(ExtractionIssue.error(type, extractor, entry, field, message));
    }

    /** Records a warning at a document location. */
    public void warning(String type, String extractor, String entry, String message) {
        add(ExtractionIssue.warning(type, extractor, entry, message));
    }

    /** Snapshot of all retained issues; safe to iterate while workers keep reporting. */
    public List<ExtractionIssue> issues() {
        synchronized (issues) {
            return List.copyOf(issues);
        }
    }

    /** Retained issues with error severity. */
    public List<ExtractionIssue> errors() {
        return issues().stream().filter(i -> i.severity() == ExtractionIssue.Severity.ERROR).toList();
    }

    /** Retained issues with warning severity. */
    public List<ExtractionIssue> warnings() {
        return issues().stream().filter(i -> i.severity() == ExtractionIssue.Severity.WARNING).toList();
    }

    /** Whether at least one error was retained. */
    public boolean hasErrors() {
        return !errors().isEmpty();
    }

    /** Number of retained errors. */
    public int errorCount() {
        return errors().size();
    }

    /** Number of retained warnings. */
    public int warningCount() {
        return warnings().size();
    }

    /** Renders the {@code errors} / {@code warnings} / {@code summary} report documented in the design. */
    public JsonObject toJson(int totalEntries, int successfulEntries) {
        JsonObject root = new JsonObject();

        JsonArray errors = new JsonArray();
        for (ExtractionIssue issue : errors()) {
            errors.add(issue.toJson());
        }
        root.add("errors", errors);

        JsonArray warnings = new JsonArray();
        for (ExtractionIssue issue : warnings()) {
            warnings.add(issue.toJson());
        }
        root.add("warnings", warnings);

        JsonObject summary = new JsonObject();
        summary.addProperty("total_entries", totalEntries);
        summary.addProperty("successful", successfulEntries);
        summary.addProperty("failed", Math.max(0, totalEntries - successfulEntries));
        summary.addProperty("warnings", warningCount());
        root.add("summary", summary);

        return root;
    }

    /** Drops all retained issues; used by tests and by a fresh run on the same collector. */
    public void clear() {
        issues.clear();
    }
}
