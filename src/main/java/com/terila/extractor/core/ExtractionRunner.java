package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.util.HashUtil;
import com.terila.extractor.util.TimeUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the whole extraction and writes the run-level metadata.
 *
 * <p>Both entrypoints (the Fabric mixin and the standalone bootstrap) go through this class so the
 * output is identical regardless of how the process was started.
 *
 * <p>Only one run may write to an output directory at a time. The runner acquires an operating
 * system lock before creating its staging directory, so a second run fails without touching the
 * first run's output.
 *
 * <p>The run has three phases: extract, validate, report. Validation reads the written documents back
 * and checks them against {@link DocumentSchemas}, so a defect is visible in the same run that
 * produced it rather than at the consumer. It runs before the report is written, so
 * {@code _errors.json} carries the validation issues together with the extraction issues.
 */
public final class ExtractionRunner {

    /** Summary document of the schema validation pass. */
    public static final String VALIDATION_FILE = OutputDirectoryManager.VALIDATION_FILE;

    private ExtractionRunner() {
    }

    /**
     * Executes every enabled extractor, validates the output, then writes {@code _meta.json},
     * {@code _errors.json} and {@code _validation.json}.
     *
     * @param config         run configuration.
     * @param registry       extractors to consider.
     * @param contextBuilder pre-populated with game handles, but not with config/writer/log.
     * @throws IOException when the output lock cannot be acquired or the output cannot be written.
     */
    public static ExtractionReport run(ExtractorConfig config, ExtractorRegistry registry,
                                       ExtractorContext.Builder contextBuilder) throws IOException {
        ExtractorLogger.setVerbose(config.verbose());
        ExtractorLogger log = ExtractorLogger.scoped("TerilaExtractor");
        ErrorCollector errors = new ErrorCollector(config.maxIssues());

        Path finalOutput = config.outputDirectory().toAbsolutePath().normalize();
        try (OutputLock lock = OutputLock.acquire(finalOutput, config.lockWait());
             OutputTransaction transaction = config.cleanOutput()
                ? OutputTransaction.begin(finalOutput)
                : null) {
            // A normal run writes into a sibling staging directory. The previous output is only
            // replaced after extraction, validation and the run-level reports all completed, so an
            // exception cannot leave a half-written tree where the last good run used to be.
            JsonWriter writer = JsonWriterFactory.create(
                    transaction != null ? transaction.stagingRoot() : finalOutput, config);
            OutputDirectoryManager output = new OutputDirectoryManager(writer);
            output.prepare();

            log.info("Terila Data Extractor %s - %s", ExtractorVersion.VERSION, ExtractorVersion.describe());
            log.info("output -> %s (%s)", finalOutput,
                    transaction != null ? "staged" : "kept");
            log.debug("output lock -> %s", lock.lockFile());

            ExtractorContext context = contextBuilder
                    .config(config)
                    .writer(writer)
                    .log(log)
                    .errors(errors)
                    .build();

            List<DataExtractor> extractors = registry.enabled(config);
            log.info("running %d extractor(s) on %d thread(s)", extractors.size(), config.threadCount());

            ExtractorExecutor executor = new ExtractorExecutor(config, log, errors);
            ExtractionReport report = executor.run(extractors, context);

            OutputValidator.Summary validation = validateOutput(config, writer, errors, log);

            writeRunMetadata(writer, report, errors, validation);

            if (transaction != null) {
                transaction.commit();
                log.info("output published -> %s", finalOutput);
            }

            log.info("done: %d/%d extractors succeeded, %d entries, %s",
                    report.successfulExtractors(), report.results().size(), report.totalEntries(),
                    TimeUtil.formatDuration(report.duration()));
            if (!errors.issues().isEmpty()) {
                log.warn("%d issue(s) reported (see %s)", errors.issues().size(),
                        OutputDirectoryManager.ERRORS_FILE);
            }
            return report;
        }
    }

    /**
     * Runs the schema validation pass when the configuration asks for it.
     *
     * @return the summary, or {@code null} when validation was disabled — the run report distinguishes
     *         "not checked" from "checked and clean".
     */
    private static OutputValidator.Summary validateOutput(ExtractorConfig config, JsonWriter writer,
                                                          ErrorCollector errors, ExtractorLogger log) {
        if (!config.validate()) {
            log.info("output validation disabled");
            return null;
        }
        OutputValidator.Summary summary = OutputValidator.validate(writer.outputRoot(), config, errors);
        log.info("validated %d/%d document(s) against %d schema(s) (%d error(s), %d warning(s))",
                summary.validated(), summary.documents(),
                DocumentSchemas.exactPaths().size() + DocumentSchemas.prefixes().size(),
                summary.errors(), summary.warnings());
        return summary;
    }

    private static void writeRunMetadata(JsonWriter writer, ExtractionReport report,
                                         ErrorCollector errors, OutputValidator.Summary validation)
            throws IOException {
        JsonObject meta = report.toJson();
        meta.addProperty("generated_at", TimeUtil.format(report.finishedAt()));

        JsonObject changelog = new JsonObject();
        changelog.addProperty("generator", "terila-extractor");
        changelog.addProperty("format", "terila-extractor-output/1");
        meta.add("changelog", changelog);

        JsonObject validationJson = new JsonObject();
        validationJson.addProperty("performed", validation != null);
        if (validation != null) {
            validationJson.add("summary", validation.toJson());
        }
        meta.add("validation", validationJson);

        // Hash of the run's own report keeps regressions visible across runs. The validation state is
        // part of the hash, so "the same data" and "the same data, still validated" differ.
        meta.addProperty("report_hash", HashUtil.sha256(meta.toString()));
        writer.write(OutputDirectoryManager.META_FILE, meta);

        writer.write(OutputDirectoryManager.ERRORS_FILE,
                errors.toJson(report.totalEntries(), report.totalEntries() - errors.errorCount()));

        writer.write(VALIDATION_FILE, validationDocument(report, validation));
    }

    /** The standalone validation report: what was checked and what the pass found. */
    private static JsonObject validationDocument(ExtractionReport report, OutputValidator.Summary validation) {
        JsonObject document = new JsonObject();
        document.add("_meta", metaOf(report, "validation"));
        document.addProperty("performed", validation != null);
        if (validation != null) {
            document.add("summary", validation.toJson());
        }

        JsonArray exact = new JsonArray();
        DocumentSchemas.exactPaths().stream().sorted().forEach(exact::add);
        JsonArray prefixes = new JsonArray();
        DocumentSchemas.prefixes().stream().sorted().forEach(prefixes::add);
        JsonObject schemas = new JsonObject();
        schemas.add("exact", exact);
        schemas.add("prefixes", prefixes);
        schemas.addProperty("count", exact.size() + prefixes.size());
        document.add("schemas", schemas);

        document.add("extractors", extractorSummary(report));
        return document;
    }

    /** One line per extractor, so the report doubles as the extraction manifest. */
    private static JsonArray extractorSummary(ExtractionReport report) {
        JsonArray extractors = new JsonArray();
        for (ExtractionReport.Result result : report.results()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", result.name());
            entry.addProperty("output", result.outputPath());
            entry.addProperty("entries", result.entryCount());
            entry.addProperty("success", result.success());
            extractors.add(entry);
        }
        return extractors;
    }

    /** Metadata block for a run-level document, matching what extractors attach to their own. */
    private static JsonObject metaOf(ExtractionReport report, String name) {
        JsonObject meta = new JsonObject();
        meta.addProperty("extractor_version", ExtractorVersion.VERSION);
        meta.addProperty("minecraft_version", ExtractorVersion.MINECRAFT_VERSION);
        meta.addProperty("protocol_version", ExtractorVersion.PROTOCOL_VERSION);
        meta.addProperty("data_pack_version", ExtractorVersion.DATA_PACK_VERSION);
        meta.addProperty("world_version", ExtractorVersion.WORLD_VERSION);
        meta.addProperty("generated_at", java.time.Instant.now().toString());
        meta.addProperty("source", "minecraft:vanilla");
        meta.addProperty("extractor_name", "run:" + name);
        meta.addProperty("entry_count", report.totalEntries());
        return meta;
    }
}

