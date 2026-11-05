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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads raw datapack files from a directory.
 *
 * <p>Some data is only ever stored as datapack files and has no runtime registry to walk
 * (advancements, item modifiers, function tags, function sources). This reader gives those
 * extractors a single deterministic access path, whether the file is JSON or a command source.
 */
public final class DatapackReader implements PackReader {

    private final Path dataRoot;

    private DatapackReader(Path dataRoot) {
        this.dataRoot = dataRoot;
    }

    /** Wraps a datapack root; {@code <root>/data} is expected to exist. */
    public static DatapackReader of(Path datapackRoot) {
        return new DatapackReader(datapackRoot.resolve("data"));
    }

    /** Whether the expected {@code data/} directory exists. */
    @Override
    public boolean isAvailable() {
        return Files.isDirectory(dataRoot);
    }

    /** Absolute data root for diagnostics. */
    @Override
    public String describe() {
        return dataRoot.toString();
    }

    /** The resolved {@code data/} directory this reader walks. */
    public Path dataRoot() {
        return dataRoot;
    }

    /** All namespaces found in the pack, sorted. */
    public List<String> namespaces() {
        if (!isAvailable()) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dataRoot)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list namespaces in " + dataRoot, e);
        }
    }

    /**
     * Loads every JSON file below {@code <root>/data/<namespace>/<directory>}.
     *
     * @return entries keyed by {@code namespace:relativePathWithoutExtension}, sorted by key.
     */
    public Map<String, JsonElement> load(String namespace, String directory) {
        Map<String, JsonElement> loaded = new TreeMap<>();
        for (Map.Entry<String, String> entry : loadText(namespace, directory, ".json").entrySet()) {
            try {
                loaded.put(entry.getKey(), JsonParser.parseString(entry.getValue()));
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Failed to parse " + entry.getKey(), failure);
            }
        }
        return loaded;
    }

    /** Loads a directory for every namespace, merging the results. */
    @Override
    public Map<String, JsonElement> loadAll(String directory) {
        Map<String, JsonElement> merged = new TreeMap<>();
        for (String namespace : namespaces()) {
            merged.putAll(load(namespace, directory));
        }
        return merged;
    }

    @Override
    public Map<String, String> loadTextAll(String directory, String extension) {
        Map<String, String> merged = new TreeMap<>();
        for (String namespace : namespaces()) {
            merged.putAll(loadText(namespace, directory, extension));
        }
        return merged;
    }

    /**
     * Loads every file with {@code extension} below one namespace's directory.
     *
     * <p>Package visible so the text path can be tested against a temporary pack without running a
     * full extraction.
     */
    Map<String, String> loadText(String namespace, String directory, String extension) {
        Map<String, String> loaded = new TreeMap<>();
        Path base = dataRoot.resolve(namespace).resolve(directory);
        if (!Files.isDirectory(base)) {
            return loaded;
        }

        List<Path> files = FileManager.listFiles(base).stream()
                .filter(path -> path.getFileName().toString().endsWith(extension))
                .sorted(Comparator.naturalOrder())
                .toList();

        for (Path file : files) {
            String relative = base.relativize(file).toString().replace('\\', '/');
            String key = namespace + ":"
                    + relative.substring(0, relative.length() - extension.length());
            try {
                loaded.put(key, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new IllegalStateException("Failed to read " + file, failure);
            }
        }
        return loaded;
    }
}

