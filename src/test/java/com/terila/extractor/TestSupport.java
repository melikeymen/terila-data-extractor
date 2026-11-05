package com.terila.extractor;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terila.extractor.bootstrap.VanillaDataLoader;
import com.terila.extractor.core.ExtractionReport;
import com.terila.extractor.core.ExtractionRunner;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorLogger;
import com.terila.extractor.core.FileManager;
import com.terila.extractor.core.InputLocator;
import com.terila.extractor.core.ReportReader;
import com.terila.extractor.extractors.Extractors;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Shared setup and helpers for the test suite.
 *
 * <p>Reading real Minecraft data needs the game's own bootstrap (version detection plus registry
 * registration), exactly like the standalone entrypoint does. Doing it once per JVM keeps the
 * integration tests affordable.
 *
 * <p>All three test classes that need extracted output share <em>one</em> run through
 * {@link #extraction()}: the pipeline takes over twenty seconds, and more importantly a second run
 * would validate different bytes than the ones the other tests assert against. The fixture is
 * therefore built once, lazily, and handed out as an immutable snapshot.
 *
 * <p>Input discovery goes through {@link InputLocator} rather than a second list of paths, so the
 * tests read the same files the tool reads and honour {@code TERILA_REPORTS} /
 * {@code TERILA_CLIENT_JAR} / {@code TERILA_MINECRAFT_DIRS}.
 */
public final class TestSupport {

    /** Output directory the shared fixture writes to. */
    public static final Path OUTPUT = Path.of("build", "test-output", "extractor");

    private static boolean bootstrapped;
    private static Extraction extraction;

    private TestSupport() {
    }

    /**
     * One completed extraction run plus the ground truth read from the live game.
     *
     * @param output               where the documents were written.
     * @param report               the run's own report.
     * @param groundTruth          values captured from the game while the data set was still open.
     * @param reportsAvailable     whether the data generator reports were found.
     * @param clientJarAvailable   whether the client jar was found.
     * @param languagesAvailable   whether the language directory (every locale) was found.
     */
    public record Extraction(Path output, ExtractionReport report, GroundTruth groundTruth,
                             boolean reportsAvailable, boolean clientJarAvailable,
                             boolean languagesAvailable) {
    }

    /**
     * Values read straight from the running game, used to cross-check the extracted documents.
     *
     * @param blockStates       total number of block states registered.
     * @param highestStateId    highest block state id in use.
     * @param recipes           recipes the loaded recipe manager holds.
     * @param structureTemplates {@code .nbt} templates in the loaded packs.
     * @param packets           packets listed by the data generator report.
     * @param tagsByRegistry    number of bound tags per registry, keyed by registry identifier.
     * @param packCensus        every data pack directory and file count, read from the loaded packs.
     */
    public record GroundTruth(int blockStates, int highestStateId, int recipes, int structureTemplates,
                              int packets, Map<String, Integer> tagsByRegistry, PackCensus packCensus) {
    }

    /**
     * A census of the loaded data pack.
     *
     * <p>Registries, tags, structure templates and function sources are extracted by different
     * sweeps, so a missing file can hide behind a perfectly valid but smaller document. Counting the
     * source files independently of the extractors is what turns “the pipeline succeeded” into
     * “every file in the pack has a home in the output”.
     *
     * @param registryFiles number of JSON files per registry path, keyed by registry identifier.
     * @param tagFiles      number of distinct tag files below {@code tags/}.
     * @param functionFiles number of {@code .mcfunction} sources below {@code function/}.
     */
    public record PackCensus(Map<String, Integer> registryFiles, int tagFiles, int functionFiles) {
    }

    /** Boots Minecraft once; safe to call from every test class. */
    public static synchronized void bootStrap() {
        if (bootstrapped) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }

    /**
     * Runs the full pipeline once and returns the shared fixture.
     *
     * <p>Every test that needs output calls this; only the first call does any work. A failure while
     * writing the output is a test infrastructure failure, so it is raised unchecked rather than
     * forcing every caller to declare {@code IOException} for a fixture they did not create.
     *
     * @throws UncheckedIOException when the pipeline could not write its output.
     */
    public static synchronized Extraction extraction() {
        if (extraction != null) {
            return extraction;
        }
        bootStrap();
        ExtractorLogger.setVerbose(false);
        FileManager.deleteRecursively(OUTPUT);

        boolean reportsAvailable = reportsRoot().isPresent();
        boolean clientJarAvailable = clientJar().isPresent();
        boolean languagesAvailable = languagesDir().isPresent();
        int packetCount = 0;
        ExtractionReport report;
        GroundTruth groundTruth;

        try (VanillaDataLoader data = VanillaDataLoader.load(null, ExtractorLogger.scoped("test"))) {
            // Ground truth has to be captured while the data set is still open.
            int blockStates = BuiltInRegistries.BLOCK.stream()
                    .mapToInt(block -> block.getStateDefinition().getPossibleStates().size())
                    .sum();
            int highestStateId = BuiltInRegistries.BLOCK.stream()
                    .flatMap(block -> block.getStateDefinition().getPossibleStates().stream())
                    .mapToInt(Block::getId)
                    .max()
                    .orElse(-1);
            int recipes = data.recipeManager().getRecipes().size();
            int templates = data.resources()
                    .listResources("structure", id -> id.getPath().endsWith(".nbt"))
                    .size();
            Map<String, Integer> tagsByRegistry = new TreeMap<>();
            for (RegistryAccess.RegistryEntry<?> registry : data.registryAccess().registries().toList()) {
                int tags = (int) registry.value().getTags().count();
                if (tags > 0) {
                    tagsByRegistry.put(registry.key().identifier().toString(), tags);
                }
            }
            PackCensus packCensus = census(data);

            ExtractorContext.Builder context = ExtractorContext.builder()
                    .registryAccess(data.registryAccess())
                    .layers(data.layers())
                    .reloadableRegistries(data.reloadableRegistries())
                    .recipeManager(data.recipeManager())
                    .commands(data.commands())
                    .functionTags(data.functionTags())
                    .pack(data.pack())
                    .resources(data.resources());
            reportsRoot().ifPresent(context::reportsRoot);
            clientJar().ifPresent(context::clientJar);

            ExtractorConfig config = ExtractorConfig.builder(OUTPUT)
                    .threadCount(4)
                    .prettyPrint(true)
                    .build();
            try {
                report = ExtractionRunner.run(config, Extractors.defaultRegistry(), context);
            } catch (IOException failure) {
                throw new UncheckedIOException("the shared extraction fixture could not be written", failure);
            }
            groundTruth = new GroundTruth(blockStates, highestStateId, recipes, templates, 0,
                    tagsByRegistry, packCensus);
        }

        if (reportsAvailable) {
            packetCount = ReportReader.of(reportsRoot().orElseThrow())
                    .read("packets.json")
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .map(TestSupport::countPackets)
                    .orElse(0);
            groundTruth = new GroundTruth(groundTruth.blockStates(), groundTruth.highestStateId(),
                    groundTruth.recipes(), groundTruth.structureTemplates(), packetCount,
                    groundTruth.tagsByRegistry(), groundTruth.packCensus());
        }

        extraction = new Extraction(OUTPUT, report, groundTruth, reportsAvailable, clientJarAvailable,
                languagesAvailable);
        return extraction;
    }

    /**
     * Counts every file of the loaded packs by the data directory it lives in.
     *
     * <p>Resource identifiers are relative to {@code data/<namespace>}, so the first path segment is
     * the directory a data pack contributor sees. Registry files are attributed to the longest
     * registry path that prefixes them ({@code worldgen/biome/x.json} belongs to
     * {@code minecraft:worldgen/biome}, not to a hypothetical {@code worldgen} registry).
     */
    private static PackCensus census(VanillaDataLoader data) {
        // Count each registry's source files through the loaded packs. Going through the resource
        // manager keeps pack overrides from being counted twice, which a raw directory walk would not.
        Map<String, Integer> registryFiles = new TreeMap<>();
        for (RegistryAccess.RegistryEntry<?> registry : data.registryAccess().registries().toList()) {
            String id = registry.key().identifier().toString();
            String path = id.substring(id.indexOf(':') + 1);
            int files = data.resources()
                    .listResources(path, resource -> resource.getPath().endsWith(".json"))
                    .size();
            if (files > 0) {
                registryFiles.put(id, files);
            }
        }

        int tagFiles = data.resources()
                .listResources("tags", resource -> resource.getPath().endsWith(".json"))
                .size();
        int functionFiles = data.resources()
                .listResources("function", resource -> resource.getPath().endsWith(".mcfunction"))
                .size();
        return new PackCensus(registryFiles, tagFiles, functionFiles);
    }

    /** The shared run's {@code _errors.json}, read back from disk. */
    public static JsonObject errors() {
        return document("_errors.json");
    }

    /** Reads a JSON array as a list of objects. */
    public static List<JsonObject> objects(com.google.gson.JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsJsonObject).toList();
    }

    /** Data generator reports, when the build has run {@code ./gradlew runDataGenerator}. */
    public static Optional<Path> reportsRoot() {
        bootStrap();
        return InputLocator.reportsRoot();
    }

    /** The downloaded client jar, when {@code ./gradlew downloadMinecraft} has run. */
    public static Optional<Path> clientJar() {
        bootStrap();
        return InputLocator.clientJar();
    }

    /** The language directory, when {@code ./gradlew downloadLanguages} has run. */
    public static Optional<Path> languagesDir() {
        bootStrap();
        return InputLocator.languagesDir();
    }

    // --- JSON helpers -----------------------------------------------------------------------

    /** Parses a JSON document, failing loudly when it cannot be read. */
    public static JsonElement read(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read " + file, failure);
        }
    }

    /** Parses a document that must be a JSON object. */
    public static JsonObject object(Path file) {
        return read(file).getAsJsonObject();
    }

    /** Parses one of the shared run's documents by output-relative path. */
    public static JsonObject document(String relativePath) {
        return object(extraction().output().resolve(relativePath));
    }

    /** Entries of a {@code {_meta, data: [...]}} document. */
    public static List<JsonObject> entries(JsonObject document) {
        return document.getAsJsonArray("data").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .toList();
    }

    public static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException failure) {
            return -1;
        }
    }

    /** Sorted view used by the ordering assertions. */
    public static <T extends Comparable<T>> List<T> sorted(List<T> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    /** Every {@code .json} file below {@code root}, depth first, in a stable order. */
    public static List<Path> jsonFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to walk " + root, failure);
        }
    }

    /** Packets listed by the data generator's {@code packets.json}. */
    public static int countPackets(JsonObject states) {
        int total = 0;
        for (JsonElement state : states.asMap().values()) {
            if (!state.isJsonObject()) {
                continue;
            }
            for (JsonElement direction : state.getAsJsonObject().asMap().values()) {
                if (direction.isJsonObject()) {
                    total += direction.getAsJsonObject().size();
                }
            }
        }
        return total;
    }

    /** Identifiers of every built-in registry, read straight from the game. */
    @SuppressWarnings("unchecked")
    public static Set<String> builtInRegistryIds() {
        Set<String> ids = new TreeSet<>();
        var root = (net.minecraft.core.Registry<net.minecraft.core.Registry<?>>)
                (net.minecraft.core.Registry<?>) BuiltInRegistries.REGISTRY;
        for (net.minecraft.core.Registry<?> registry : root) {
            Identifier id = root.getKey(registry);
            if (id != null && !id.toString().equals("minecraft:root")) {
                ids.add(id.toString());
            }
        }
        return ids;
    }
}

