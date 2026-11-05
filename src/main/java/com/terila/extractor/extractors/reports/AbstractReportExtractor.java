package com.terila.extractor.extractors.reports;

// Written by AI
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ReportReader;

/**
 * Base class for the extractors that read Minecraft's own data generator reports.
 *
 * <p>The reports are the only source for protocol level data: the game writes what it cannot expose
 * through an API. Each subclass names the report it needs; when the reports are missing the run
 * reports a single warning instead of writing an empty document, so a caller can tell the difference
 * between "vanilla has no such data" and "the input was not provided".
 */
public abstract class AbstractReportExtractor extends AbstractExtractor {

    private final String reportName;

    /**
     * @param name       stable identifier reported in logs and {@code _meta}.
     * @param type       category that controls whether the extractor runs.
     * @param outputPath document written relative to the output root.
     * @param reportName report inside the reports directory, e.g. {@code blocks.json}.
     */
    protected AbstractReportExtractor(String name, DataType type, String outputPath, String reportName) {
        super(name, type, outputPath);
        this.reportName = reportName;
    }

    /** Report file this extractor reads, e.g. {@code blocks.json}. */
    public String reportName() {
        return reportName;
    }

    /**
     * Returns the reports reader, or {@code null} after recording a warning.
     *
     * @param context the run context.
     * @return the reader for the reports directory, or {@code null} when the input was not provided.
     */
    protected ReportReader reports(ExtractorContext context) {
        ReportReader reader = context.reports().orElse(null);
        if (reader == null) {
            context.errors().warning("REPORTS_UNAVAILABLE", name(), reportName,
                    "No data generator reports found; run ./gradlew runDataGenerator "
                            + "or pass --reports <dir>");
        }
        return reader;
    }
}
