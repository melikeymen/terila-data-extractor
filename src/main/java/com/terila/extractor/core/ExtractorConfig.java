package com.terila.extractor.core;

// Written by AI
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime configuration for an extraction run.
 *
 * <p>Immutable; use {@link #builder()} to construct one.
 */
public final class ExtractorConfig {

    /**
     * Digits kept for every extracted float.
     *
     * <p>Six is enough for every value the game uses (block hardness, jump factors, knockback
     * resistance) and short enough that a float's binary noise never reaches the output, which is
     * what keeps repeated runs byte-identical.
     */
    public static final int DEFAULT_FLOAT_PRECISION = 6;

    /**
     * Upper bound on recorded issues.
     *
     * <p>A broken run can raise one issue per entry — tens of thousands of them — and a report that
     * large is unreadable. The first issues are the informative ones, so the collector stops after
     * this many and records that it did.
     */
    public static final int DEFAULT_MAX_ISSUES = 10_000;

    private final Path outputDirectory;
    private final int threadCount;
    private final int floatPrecision;
    private final int maxIssues;
    private final boolean prettyPrint;
    private final boolean gzip;
    private final boolean failFast;
    private final boolean verbose;
    private final boolean validate;
    private final boolean copyBinaryAssets;
    private final boolean cleanOutput;
    private final Duration lockWait;
    private final NbtFormat nbtFormat;
    private final Set<DataType> enabledTypes;

    private ExtractorConfig(Builder builder) {
        this.outputDirectory = builder.outputDirectory;
        this.threadCount = builder.threadCount;
        this.floatPrecision = builder.floatPrecision;
        this.maxIssues = builder.maxIssues;
        this.prettyPrint = builder.prettyPrint;
        this.gzip = builder.gzip;
        this.failFast = builder.failFast;
        this.verbose = builder.verbose;
        this.validate = builder.validate;
        this.copyBinaryAssets = builder.copyBinaryAssets;
        this.cleanOutput = builder.cleanOutput;
        this.lockWait = builder.lockWait;
        this.nbtFormat = builder.nbtFormat;
        this.enabledTypes = builder.enabledTypes.isEmpty()
                ? EnumSet.allOf(DataType.class)
                : EnumSet.copyOf(builder.enabledTypes);
    }

    /** Root the run writes its documents into. */
    public Path outputDirectory() {
        return outputDirectory;
    }

    /** How many extractors may run at once; never below {@code 1}. */
    public int threadCount() {
        return threadCount;
    }

    /** Digits kept for extracted floats; see {@link #DEFAULT_FLOAT_PRECISION}. */
    public int floatPrecision() {
        return floatPrecision;
    }

    /** Upper bound on recorded issues; see {@link #DEFAULT_MAX_ISSUES}. */
    public int maxIssues() {
        return maxIssues;
    }

    /** Whether documents are indented; compact output is smaller and faster to parse. */
    public boolean prettyPrint() {
        return prettyPrint;
    }

    /** Whether documents are written as {@code .json.gz} instead of {@code .json}. */
    public boolean gzip() {
        return gzip;
    }

    /** Whether the first failing extractor aborts the run instead of being recorded. */
    public boolean failFast() {
        return failFast;
    }

    /** Whether per-extractor progress is logged. */
    public boolean verbose() {
        return verbose;
    }

    /**
     * Whether the written output is validated against the document schemas at the end of a run.
     *
     * <p>On by default: it is the check that turns "an extractor ran" into "an extractor produced
     * the shape it promised", and it costs a fraction of the extraction itself.
     */
    public boolean validate() {
        return validate;
    }

    /**
     * Whether binary client assets (textures, sounds, shaders) are copied into the output.
     *
     * <p>Off by default: they are always indexed with size and CRC, and copying several hundred
     * megabytes is rarely what a data consumer wants. Turn it on to get a directory that works as a
     * resource pack as-is.
     */
    public boolean copyBinaryAssets() {
        return copyBinaryAssets;
    }

    /**
     * Whether the managed output is removed before extraction starts.
     *
     * <p>On by default: a previous run's documents and reports would otherwise be validated and
     * counted as if the current run had produced them. Truly unrelated files outside the managed
     * layout are never touched.
     */
    public boolean cleanOutput() {
        return cleanOutput;
    }

    /**
     * How long to wait for another run to release the output directory lock.
     *
     * <p>Zero is the default: a locked output fails immediately. A positive duration lets a
     * scheduled or wrapper-driven run wait for the owner instead of failing spuriously.
     */
    public Duration lockWait() {
        return lockWait;
    }

    /** How NBT is emitted: wire payloads, raw per-entry files, or both. */
    public NbtFormat nbtFormat() {
        return nbtFormat;
    }

    /** The categories enabled for this run; an empty builder selection means "all". */
    public Set<DataType> enabledTypes() {
        return Set.copyOf(enabledTypes);
    }

    /** Whether extractors of {@code type} should run. */
    public boolean isEnabled(DataType type) {
        return enabledTypes.contains(type);
    }

    /** Creates a configuration builder with the documented defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a builder whose output root is already set. */
    public static Builder builder(Path outputDirectory) {
        return new Builder().outputDirectory(outputDirectory);
    }

    /** Mutable builder for {@link ExtractorConfig}; every setter returns {@code this}. */
    public static final class Builder {

        private Path outputDirectory = Path.of("extractor_output");
        private int threadCount = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        private int floatPrecision = DEFAULT_FLOAT_PRECISION;
        private int maxIssues = DEFAULT_MAX_ISSUES;
        private boolean prettyPrint = true;
        private boolean gzip = false;
        private boolean failFast = false;
        private boolean verbose = false;
        private boolean validate = true;
        private boolean copyBinaryAssets = false;
        private boolean cleanOutput = true;
        private Duration lockWait = Duration.ZERO;
        private NbtFormat nbtFormat = NbtFormat.WIRE;
        private final Set<DataType> enabledTypes = EnumSet.noneOf(DataType.class);

        /** Sets the root directory all generated documents are written under. */
        public Builder outputDirectory(Path outputDirectory) {
            this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
            return this;
        }

        /** Sets the number of extractors that may run concurrently (at least one). */
        public Builder threadCount(int threadCount) {
            if (threadCount < 1) {
                throw new IllegalArgumentException("threadCount must be >= 1");
            }
            this.threadCount = threadCount;
            return this;
        }

        /** Digits kept for extracted floats. Must be positive; 6 is the documented default. */
        public Builder floatPrecision(int floatPrecision) {
            if (floatPrecision < 1) {
                throw new IllegalArgumentException("floatPrecision must be >= 1");
            }
            this.floatPrecision = floatPrecision;
            return this;
        }

        /** Upper bound on recorded issues. Must be positive. */
        public Builder maxIssues(int maxIssues) {
            if (maxIssues < 1) {
                throw new IllegalArgumentException("maxIssues must be >= 1");
            }
            this.maxIssues = maxIssues;
            return this;
        }

        /** Selects indented JSON instead of compact JSON. */
        public Builder prettyPrint(boolean prettyPrint) {
            this.prettyPrint = prettyPrint;
            return this;
        }

        /** Compresses JSON documents with gzip when enabled. */
        public Builder gzip(boolean gzip) {
            this.gzip = gzip;
            return this;
        }

        /** Aborts on the first extractor failure instead of recording it and continuing. */
        public Builder failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        /** Enables per-extractor progress logging. */
        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        /** Enables the end-of-run schema validation pass. */
        public Builder validate(boolean validate) {
            this.validate = validate;
            return this;
        }

        /** Copy binary client assets instead of only indexing them. */
        public Builder copyBinaryAssets(boolean copyBinaryAssets) {
            this.copyBinaryAssets = copyBinaryAssets;
            return this;
        }

        /** Keep an existing output tree instead of removing the managed layout first. */
        public Builder cleanOutput(boolean cleanOutput) {
            this.cleanOutput = cleanOutput;
            return this;
        }

        /** Maximum time to wait for a concurrent run to release the output lock. */
        public Builder lockWait(Duration lockWait) {
            this.lockWait = Objects.requireNonNull(lockWait, "lockWait");
            if (lockWait.isNegative()) {
                throw new IllegalArgumentException("lockWait must not be negative");
            }
            return this;
        }

        /** Select wire, raw or both NBT output. Defaults to {@link NbtFormat#WIRE}. */
        public Builder nbtFormat(NbtFormat nbtFormat) {
            this.nbtFormat = Objects.requireNonNull(nbtFormat, "nbtFormat");
            return this;
        }

        /** Adds data categories to the enabled set. */
        public Builder enable(DataType... types) {
            this.enabledTypes.addAll(Set.of(types));
            return this;
        }

        /** Clears the selection so every category runs. */
        public Builder enableAll() {
            this.enabledTypes.clear();
            return this;
        }

        /** Freezes the current values into an immutable configuration. */
        public ExtractorConfig build() {
            return new ExtractorConfig(this);
        }
    }
}

