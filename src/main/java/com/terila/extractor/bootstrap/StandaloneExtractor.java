package com.terila.extractor.bootstrap;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractionReport;
import com.terila.extractor.core.ExtractionRunner;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorLogger;
import com.terila.extractor.core.InputLocator;
import com.terila.extractor.core.NbtFormat;
import com.terila.extractor.core.OutputLockedException;
import com.terila.extractor.extractors.Extractors;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Head-less entry point of the extractor.
 *
 * <p>Boots Minecraft's own bootstrap (the same sequence the vanilla server uses) and then runs the
 * identical pipeline the in-game entrypoint runs. This is what makes the tool scriptable and
 * verifiable without launching a game:
 *
 * <pre>{@code
 * ./gradlew runExtractor --args="--output build/extractor_output --threads 8"
 * }</pre>
 *
 * <p>Coverage matches a running server: {@link VanillaDataLoader} replays the vanilla data pack
 * load, so datapack registries (biomes, features, structures, enchantments, damage types, trims,
 * variants, dialogs), the static tags, recipes, advancements, the command tree and every NBT
 * structure template are all available. One or more extra data packs can be stacked on top of the
 * vanilla one by repeating {@code --datapack}; later packs win, as they do in a server.
 *
 * <p>The two inputs the game itself has to produce are discovered automatically, so a plain run
 * already covers everything: the data generator reports written by
 * {@code ./gradlew runDataGenerator} ({@code build/datagen}) and the client jar downloaded into
 * {@code .mc-cache} (resource-pack assets and language files). Both can still be pointed at
 * explicitly with {@code --reports} and {@code --client-jar}.
 */
public final class StandaloneExtractor {

    /** Exit status for a second run against an output directory that is already locked. */
    private static final int EXIT_LOCKED = 4;

    private StandaloneExtractor() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException invalid) {
            System.err.println("error: " + invalid.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (options.help) {
            printUsage();
            return;
        }

        // Vanilla boot sequence: detect the version, then bootstrap registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        int status = run(options);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Runs one extraction after the game has been bootstrapped.
     *
     * <p>Returns the process exit code instead of terminating the JVM, so tests can drive the
     * head-less path without spawning a subprocess.
     */
    static int run(Options options) {
        // Discovery needs the detected version (the cache paths are versioned), so it happens here
        // rather than while parsing the command line.
        options.discoverMissingInputs();

        ExtractorLogger.setVerbose(options.verbose);
        ExtractorLogger log = ExtractorLogger.scoped("StandaloneExtractor");
        log.info("bootstrapped Minecraft %s (protocol %d)",
                SharedConstants.getCurrentVersion().name(),
                SharedConstants.getCurrentVersion().protocolVersion());

        ExtractorConfig.Builder configBuilder = ExtractorConfig.builder(options.output)
                .threadCount(options.threads)
                .prettyPrint(!options.compact)
                .gzip(options.gzip)
                .verbose(options.verbose)
                .failFast(options.failFast)
                .validate(options.validate)
                .copyBinaryAssets(options.copyBinaryAssets)
                .cleanOutput(options.clean)
                .nbtFormat(options.nbtFormat)
                .lockWait(options.lockWait);

        if (!options.only.isEmpty()) {
            configBuilder.enable(options.only.toArray(new DataType[0]));
        }

        VanillaDataLoader data;
        try {
            data = VanillaDataLoader.loadPacks(options.datapacks, log);
        } catch (RuntimeException failure) {
            log.error("could not load the Minecraft data set", failure);
            return 3;
        }

        ExtractorContext.Builder contextBuilder = ExtractorContext.builder()
                .registryAccess(data.registryAccess())
                .layers(data.layers())
                .reloadableRegistries(data.reloadableRegistries())
                .recipeManager(data.recipeManager())
                .commands(data.commands())
                .functionTags(data.functionTags())
                .pack(data.pack())
                .resources(data.resources());

        // The loaded pack is already the authoritative raw view for every overlay. Keep a single
        // directory as the fallback for a context that has no loaded pack; a stack has no such
        // single-directory equivalent.
        if (options.datapacks.size() == 1) {
            contextBuilder.datapackRoot(options.datapacks.get(0));
        }
        if (options.reports != null) {
            contextBuilder.reportsRoot(options.reports);
            log.info("reports root  -> %s", options.reports);
        } else {
            log.warn("no data generator reports found; packet ids, block state ids and the report "
                    + "based data sets were skipped (run ./gradlew runDataGenerator, or pass --reports)");
        }
        if (options.languagesDir != null) {
            contextBuilder.languagesDir(options.languagesDir);
            log.info("languages     -> %s", options.languagesDir);
        } else {
            log.warn("no language directory found; only en_us.json from the client jar is extracted "
                    + "(run ./gradlew downloadLanguages, or pass --lang-dir)");
        }
        if (options.clientJar != null) {
            contextBuilder.clientJar(options.clientJar);
            log.info("client jar    -> %s", options.clientJar);
        } else {
            log.warn("no client jar found; translations and resource pack assets were skipped "
                    + "(pass --client-jar)");
        }

        try (data) {
            ExtractionReport report = ExtractionRunner.run(configBuilder.build(),
                    Extractors.defaultRegistry(), contextBuilder);
            return report.isFullySuccessful() ? 0 : 1;
        } catch (OutputLockedException locked) {
            log.error("%s", locked.getMessage());
            return EXIT_LOCKED;
        } catch (Exception failure) {
            log.error("extraction failed", failure);
            return 3;
        }
    }

    private static void printUsage() {
        System.out.println("""
                Terila Data Extractor (head-less)

                Usage: StandaloneExtractor [options]

                  --output <dir>     output directory (default: extractor_output)
                  --datapack <dir>   data pack stacked on top of the vanilla pack
                                     (repeatable; later packs win)
                  --reports <dir>    data-generator reports directory (packet ids)
                  --client-jar <jar> Minecraft client jar (language files and assets)
                  --lang-dir <dir>   directory of <locale>.json language files (all locales)
                  --threads <n>      worker threads (default: min(cores, 8))
                  --only <types>     comma separated DataType names (BLOCKS,ITEMS,...)
                  --compact          minified JSON instead of pretty printing
                  --gzip             gzip the output files
                  --nbt-format <f>   NBT output: wire (default), raw, or both
                  --copy-binary-assets
                                     copy textures/sounds/shaders instead of indexing them
                  --no-validate      skip the JSON schema validation pass
                  --no-clean         keep documents from previous runs
                  --wait-for-lock <s> wait up to s seconds for another run to release the output
                  --fail-fast        stop after the first level with failures
                  --verbose          debug logging
                  --help             this message

                Environment:
                  TERILA_REPORTS         data-generator reports directory
                  TERILA_CLIENT_JAR      client jar to read language files and assets from
                  TERILA_LANGUAGES       directory of <locale>.json files (./gradlew downloadLanguages)
                  TERILA_MINECRAFT_DIRS  path-separated extra directories searched for those two

                Exit status:
                  0  every extractor succeeded
                  1  one or more extractors failed
                  2  invalid command line
                  3  data loading or extraction failed
                  4  the output directory is used by another run
                """);
    }

    /** Parsed command line options. */
    static final class Options {

        Path output = Path.of("extractor_output");
        List<Path> datapacks = new ArrayList<>();
        Path reports;
        Path clientJar;
        Path languagesDir;
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        Set<DataType> only = EnumSet.noneOf(DataType.class);
        boolean compact;
        boolean gzip;
        NbtFormat nbtFormat = NbtFormat.WIRE;
        boolean failFast;
        boolean verbose;
        boolean validate = true;
        boolean copyBinaryAssets;
        boolean clean = true;
        Duration lockWait = Duration.ZERO;
        boolean help;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--nbt-format=")) {
                    options.nbtFormat = NbtFormat.parse(arg.substring("--nbt-format=".length()));
                    continue;
                }
                switch (arg) {
                    case "--output", "-o" -> options.output = Path.of(requireValue(args, ++i, arg));
                    case "--datapack" -> options.datapacks.add(Path.of(requireValue(args, ++i, arg)));
                    case "--reports" -> options.reports = Path.of(requireValue(args, ++i, arg));
                    case "--client-jar" -> options.clientJar = Path.of(requireValue(args, ++i, arg));
                    case "--lang-dir" -> options.languagesDir = Path.of(requireValue(args, ++i, arg));
                    case "--threads" -> options.threads = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--only" -> options.only = parseTypes(requireValue(args, ++i, arg));
                    case "--compact" -> options.compact = true;
                    case "--gzip" -> options.gzip = true;
                    case "--nbt-format" -> options.nbtFormat = NbtFormat.parse(
                            requireValue(args, ++i, arg));
                    case "--copy-binary-assets" -> options.copyBinaryAssets = true;
                    case "--no-validate" -> options.validate = false;
                    case "--no-clean" -> options.clean = false;
                    case "--wait-for-lock" -> options.lockWait = parseLockWait(
                            requireValue(args, ++i, arg), arg);
                    case "--fail-fast" -> options.failFast = true;
                    case "--verbose", "-v" -> options.verbose = true;
                    case "--help", "-h" -> options.help = true;
                    default -> throw new IllegalArgumentException("unknown option: " + arg);
                }
            }
            if (options.threads < 1) {
                throw new IllegalArgumentException("--threads must be >= 1");
            }
            return options;
        }

        /**
         * Fills in the two game-produced inputs when the caller did not name them.
         *
         * <p>Both live in well known build locations, so the tool works out of the box after
         * {@code ./gradlew extractAll} without any arguments. The search order lives in
         * {@link InputLocator} so the in-game entrypoint and the tests look in the same places.
         */
        void discoverMissingInputs() {
            if (reports == null) {
                reports = InputLocator.reportsRoot().orElse(null);
            }
            if (clientJar == null) {
                clientJar = InputLocator.clientJar().orElse(null);
            }
            if (languagesDir == null) {
                languagesDir = InputLocator.languagesDir().orElse(null);
            }
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static Duration parseLockWait(String value, String option) {
            try {
                long seconds = Long.parseLong(value);
                if (seconds < 0) {
                    throw new IllegalArgumentException(option + " must be >= 0");
                }
                return Duration.ofSeconds(seconds);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(option + " must be a whole number of seconds: "
                        + value);
            }
        }

        private static Set<DataType> parseTypes(String value) {
            Set<DataType> types = EnumSet.noneOf(DataType.class);
            for (String token : value.split(",")) {
                String name = token.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                try {
                    types.add(DataType.valueOf(name));
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException("unknown data type: " + token);
                }
            }
            return types;
        }
    }
}

