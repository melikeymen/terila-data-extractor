package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Outcome of a whole run: one {@link Result} per extractor plus totals.
 */
public final class ExtractionReport {

    /**
     * Result of a single extractor.
     *
     * @param name           the extractor's stable identifier.
     * @param type           category the extractor belongs to.
     * @param outputPath     document the extractor wrote, relative to the output root.
     * @param success        whether the extractor completed; errors never abort the whole run.
     * @param entryCount     top-level entries emitted.
     * @param durationMillis wall-clock time the extractor took.
     * @param error          failure detail when {@code success} is {@code false}.
     */
    public record Result(
            String name,
            DataType type,
            String outputPath,
            boolean success,
            int entryCount,
            long durationMillis,
            String error
    ) {
        /** Renders this result for {@code _meta.json}. */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("type", type.name());
            json.addProperty("output", outputPath);
            json.addProperty("success", success);
            json.addProperty("entry_count", entryCount);
            json.addProperty("duration_ms", durationMillis);
            if (error != null) {
                json.addProperty("error", error);
            }
            return json;
        }
    }

    private final Instant startedAt;
    private final Instant finishedAt;
    private final List<Result> results;

    /** Creates an immutable report; the result list is copied and may not be null. */
    public ExtractionReport(Instant startedAt, Instant finishedAt, List<Result> results) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.results = List.copyOf(results);
    }

    /** Wall-clock start of the run. */
    public Instant startedAt() {
        return startedAt;
    }

    /** Wall-clock end of the run. */
    public Instant finishedAt() {
        return finishedAt;
    }

    /** Elapsed time between the two timestamps. */
    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    /** One result per extractor, in execution order. */
    public List<Result> results() {
        return results;
    }

    /** Results whose extractor did not complete successfully. */
    public List<Result> failures() {
        return results.stream().filter(r -> !r.success()).toList();
    }

    /** Number of extractors that completed successfully. */
    public int successfulExtractors() {
        return (int) results.stream().filter(Result::success).count();
    }

    /** Number of extractors that failed. */
    public int failedExtractors() {
        return results.size() - successfulExtractors();
    }

    /** Sum of the top-level entry counts reported by the extractors. */
    public int totalEntries() {
        return results.stream().mapToInt(Result::entryCount).sum();
    }

    /** Whether every extractor completed successfully. */
    public boolean isFullySuccessful() {
        return failedExtractors() == 0;
    }

    /** Renders the run report written to {@code _meta.json}. */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("minecraft_version", ExtractorVersion.MINECRAFT_VERSION);
        root.addProperty("protocol_version", ExtractorVersion.PROTOCOL_VERSION);
        root.addProperty("world_version", ExtractorVersion.WORLD_VERSION);
        root.addProperty("data_pack_version", ExtractorVersion.DATA_PACK_VERSION);
        root.addProperty("resource_pack_version", ExtractorVersion.RESOURCE_PACK_VERSION);
        root.addProperty("extractor_version", ExtractorVersion.VERSION);
        root.addProperty("generated_at", finishedAt.toString());
        root.addProperty("duration_ms", duration().toMillis());
        root.addProperty("total_extractors", results.size());
        root.addProperty("successful_extractors", successfulExtractors());
        root.addProperty("failed_extractors", failedExtractors());
        root.addProperty("total_entries", totalEntries());

        JsonArray extractors = new JsonArray();
        for (Result result : results) {
            extractors.add(result.toJson());
        }
        root.add("extractors", extractors);
        return root;
    }
}
