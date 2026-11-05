package com.terila.extractor.core;

// Written by AI
import java.util.Set;

/**
 * A unit of extraction. Each implementation owns exactly one data category and writes the files it
 * is responsible for.
 *
 * <p>Implementations must be idempotent and deterministic: the same game data has to produce
 * byte-identical output, which the pipeline relies on when comparing runs.
 */
public interface DataExtractor {

    /**
     * Stable identifier, e.g. {@code blocks}. Used in logs and reports.
     *
     * @return the extractor's name; unique within a registry.
     */
    String name();

    /**
     * Category this extractor belongs to; drives {@link ExtractorConfig#isEnabled}.
     *
     * @return the category used to enable or skip this extractor for a run.
     */
    DataType type();

    /**
     * Output path relative to the output root, e.g. {@code blocks/minecraft.json}.
     *
     * @return the primary document this extractor writes.
     */
    String outputPath();

    /**
     * Extractors that must have completed before this one runs. Ordering is resolved by
     * {@link ExtractorPipeline}; unrelated extractors run in parallel.
     *
     * @return the extractor classes whose output this one reads.
     */
    default Set<Class<? extends DataExtractor>> dependencies() {
        return Set.of();
    }

    /**
     * Performs the extraction.
     *
     * @param context everything the extractor may read: registries, reports, the writer and the
     *                error collector.
     * @return number of top-level entries emitted, used for the run summary.
     * @throws Exception so that an extractor may fail however its library does; the pipeline records
     *                   the failure and continues with the remaining extractors.
     */
    int extract(ExtractorContext context) throws Exception;
}
