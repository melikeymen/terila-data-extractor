package com.terila.extractor.core;

// Written by AI
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordered collection of extractors plus lookup helpers.
 *
 * <p>Registration order is preserved and doubles as the deterministic baseline; the pipeline may
 * still reorder entries to satisfy dependencies.
 */
public final class ExtractorRegistry {

    private final Map<String, DataExtractor> byName = new LinkedHashMap<>();

    /** Registers one extractor; duplicate names are rejected before they can shadow each other. */
    public ExtractorRegistry register(DataExtractor extractor) {
        Objects.requireNonNull(extractor, "extractor");
        DataExtractor previous = byName.putIfAbsent(extractor.name(), extractor);
        if (previous != null) {
            throw new IllegalStateException("Duplicate extractor name: " + extractor.name());
        }
        return this;
    }

    /** Registers every extractor in the supplied collection, preserving iteration order. */
    public ExtractorRegistry registerAll(Collection<? extends DataExtractor> extractors) {
        extractors.forEach(this::register);
        return this;
    }

    /** Every registered extractor, in registration order. */
    public List<DataExtractor> all() {
        return List.copyOf(byName.values());
    }

    /** Looks up an extractor by its stable name. */
    public Optional<DataExtractor> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Finds the first registered extractor assignable to {@code type}. */
    public <T extends DataExtractor> Optional<T> byClass(Class<T> type) {
        return byName.values().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    /** Registered extractors belonging to one output category. */
    public List<DataExtractor> byType(DataType type) {
        return byName.values().stream().filter(e -> e.type() == type).toList();
    }

    /** Extractors enabled by the supplied configuration, in registration order. */
    public List<DataExtractor> enabled(ExtractorConfig config) {
        List<DataExtractor> enabled = new ArrayList<>();
        for (DataExtractor extractor : byName.values()) {
            if (config.isEnabled(extractor.type())) {
                enabled.add(extractor);
            }
        }
        return List.copyOf(enabled);
    }

    /** Number of registered extractors. */
    public int size() {
        return byName.size();
    }
}
