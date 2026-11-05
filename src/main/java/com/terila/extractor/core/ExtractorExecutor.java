package com.terila.extractor.core;

// Written by AI
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs extractors on a thread pool, one level at a time.
 *
 * <p>Error isolation is the point of this class: an extractor that throws is recorded as a failure
 * and the remaining extractors still run. Only {@link ExtractorConfig#failFast()} turns a failure
 * into an abort.
 */
public final class ExtractorExecutor {

    private final ExtractorConfig config;
    private final ExtractorLogger log;
    private final ErrorCollector errors;

    public ExtractorExecutor(ExtractorConfig config, ExtractorLogger log, ErrorCollector errors) {
        this.config = config;
        this.log = log;
        this.errors = errors;
    }

    public ExtractionReport run(List<DataExtractor> extractors, ExtractorContext context) {
        Instant startedAt = Instant.now();
        ProgressReporter progress = new ProgressReporter(log, extractors.size());

        List<List<DataExtractor>> levels = ExtractorPipeline.levels(extractors);
        log.debug("pipeline resolved into %d level(s) for %d extractor(s)", levels.size(), extractors.size());

        List<ExtractionReport.Result> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(config.threadCount(), Math.max(1, extractors.size()))),
                runnable -> {
                    Thread thread = new Thread(runnable, "terila-extractor-worker");
                    thread.setDaemon(true);
                    return thread;
                });

        try {
            for (List<DataExtractor> level : levels) {
                List<Future<ExtractionReport.Result>> futures = new ArrayList<>(level.size());
                for (DataExtractor extractor : level) {
                    futures.add(pool.submit(() -> execute(extractor, context, progress)));
                }

                for (Future<ExtractionReport.Result> future : futures) {
                    try {
                        results.add(future.get());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Extraction interrupted", interrupted);
                    } catch (ExecutionException unexpected) {
                        // execute() already converts failures into results; this is a guard.
                        throw new IllegalStateException("Unexpected executor failure", unexpected.getCause());
                    }
                }

                if (config.failFast() && results.stream().anyMatch(r -> !r.success())) {
                    log.warn("failFast enabled: stopping after level with failures");
                    break;
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        return new ExtractionReport(startedAt, Instant.now(), results);
    }

    private ExtractionReport.Result execute(DataExtractor extractor, ExtractorContext context,
                                            ProgressReporter progress) {
        if (!config.isEnabled(extractor.type())) {
            return new ExtractionReport.Result(extractor.name(), extractor.type(), extractor.outputPath(),
                    true, 0, 0L, null);
        }

        progress.started(extractor.name());
        long start = System.nanoTime();
        try {
            int entries = extractor.extract(context);
            long millis = (System.nanoTime() - start) / 1_000_000L;
            progress.completed(extractor.name(), entries, millis);
            return new ExtractionReport.Result(extractor.name(), extractor.type(), extractor.outputPath(),
                    true, entries, millis, null);
        } catch (Throwable failure) {
            long millis = (System.nanoTime() - start) / 1_000_000L;
            progress.failed(extractor.name(), failure);
            errors.error("EXTRACTOR_FAILED", extractor.name(), null, null,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return new ExtractionReport.Result(extractor.name(), extractor.type(), extractor.outputPath(),
                    false, 0, millis, failure.toString());
        }
    }
}
