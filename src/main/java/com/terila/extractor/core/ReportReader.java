package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Reads the files Minecraft's own data generator writes.
 *
 * <p>Some of the most valuable data is not reachable through any runtime API: packet ids, the global
 * per-state block ids, registry protocol ids, the command tree in its serialised form, the default
 * item components and the new JSON-RPC method table. The game writes all of it to
 * {@code reports/} when {@code net.minecraft.data.Main} runs, so that directory is the authoritative
 * source.
 *
 * <p>The reader accepts either the data generator's output directory or the {@code reports}
 * directory inside it, so {@code --reports build/datagen} and {@code --reports build/datagen/reports}
 * both work. Documents are parsed once and cached, since several extractors read the same reports.
 */
public final class ReportReader {

    /** Report file names that identify a directory as the reports root. */
    private static final String MARKER = "registries.json";

    private final Path root;
    private final Map<String, Optional<JsonElement>> cache = new ConcurrentHashMap<>();

    private ReportReader(Path root) {
        this.root = root;
    }

    /**
     * Wraps a reports directory.
     *
     * @param candidate either the data generator output directory (containing {@code reports/}) or
     *                  the reports directory itself.
     */
    public static ReportReader of(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path nested = normalized.resolve("reports");
        if (Files.isDirectory(nested) && !Files.isRegularFile(normalized.resolve(MARKER))) {
            return new ReportReader(nested);
        }
        return new ReportReader(normalized);
    }

    /** Whether the directory actually contains the marker report. */
    public boolean isAvailable() {
        return Files.isRegularFile(root.resolve(MARKER));
    }

    /** Human-readable location for logs and warning messages. */
    public String describe() {
        return root.toString();
    }

    /** Reports root that was resolved from the supplied candidate. */
    public Path root() {
        return root;
    }

    /** Absolute path of a report file, whether or not it exists. */
    public Path path(String relativeName) {
        return root.resolve(relativeName);
    }

    /** Whether a named report exists, without parsing it. */
    public boolean has(String relativeName) {
        return Files.isRegularFile(path(relativeName));
    }

    /** Parses one report, e.g. {@code "reports/packets.json"} → {@code "packets.json"}. */
    public Optional<JsonElement> read(String relativeName) {
        return cache.computeIfAbsent(relativeName, name -> {
            Path file = path(name);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException failure) {
                throw new UncheckedIOException("Failed to read report " + file, failure);
            }
        });
    }

    /**
     * Reads every {@code *.json} file directly below a report sub-directory.
     *
     * @return entries keyed by file name without the {@code .json} extension, in file name order.
     */
    public Map<String, JsonElement> readDirectory(String relativeDirectory) {
        Map<String, JsonElement> loaded = new TreeMap<>();
        Path directory = root.resolve(relativeDirectory);
        if (!Files.isDirectory(directory)) {
            return loaded;
        }

        for (Path file : listJson(directory)) {
            String name = file.getFileName().toString();
            String key = name.substring(0, name.length() - ".json".length());
            read(relativeDirectory + "/" + name).ifPresent(value -> loaded.put(key, value));
        }
        return loaded;
    }

    /** Reads every {@code *.json} file below a report sub-tree, recursively. */
    public Map<String, JsonElement> readTree(String relativeDirectory) {
        Map<String, JsonElement> loaded = new LinkedHashMap<>();
        Path directory = root.resolve(relativeDirectory);
        if (!Files.isDirectory(directory)) {
            return loaded;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.naturalOrder())
                    .toList()) {
                String relative = directory.relativize(file).toString().replace('\\', '/');
                String key = relative.substring(0, relative.length() - ".json".length());
                read(relativeDirectory + "/" + relative).ifPresent(value -> loaded.put(key, value));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to walk report directory " + directory, failure);
        }
        return loaded;
    }

    private static java.util.List<Path> listJson(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to list report directory " + directory, failure);
        }
    }
}
