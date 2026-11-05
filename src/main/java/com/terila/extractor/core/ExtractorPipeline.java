package com.terila.extractor.core;

// Written by AI
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a flat extractor list into execution levels.
 *
 * <p>Level {@code 0} holds extractors without dependencies; every later level only contains
 * extractors whose dependencies all live in earlier levels. All extractors inside one level are
 * independent of each other and can be executed in parallel.
 *
 * <p>Uses Kahn's algorithm and reports a cycle instead of looping forever.
 */
public final class ExtractorPipeline {

    private ExtractorPipeline() {
    }

    /** Ordered levels; level {@code 0} first. */
    public static List<List<DataExtractor>> levels(List<DataExtractor> extractors) {
        Map<Class<? extends DataExtractor>, DataExtractor> byClass = new HashMap<>();
        for (DataExtractor extractor : extractors) {
            byClass.put(extractor.getClass(), extractor);
        }

        Map<DataExtractor, Set<DataExtractor>> edges = new HashMap<>();
        Map<DataExtractor, Integer> inDegree = new HashMap<>();
        for (DataExtractor extractor : extractors) {
            edges.putIfAbsent(extractor, new LinkedHashSet<>());
            inDegree.putIfAbsent(extractor, 0);
        }

        for (DataExtractor extractor : extractors) {
            for (Class<? extends DataExtractor> dependency : extractor.dependencies()) {
                DataExtractor producer = byClass.get(dependency);
                if (producer == null) {
                    // Dependency is disabled or not registered: nothing to order against.
                    continue;
                }
                if (producer == extractor) {
                    throw new IllegalStateException(extractor.name() + " depends on itself");
                }
                if (edges.get(producer).add(extractor)) {
                    inDegree.merge(extractor, 1, Integer::sum);
                }
            }
        }

        Deque<DataExtractor> ready = new ArrayDeque<>();
        for (DataExtractor extractor : extractors) {
            if (inDegree.get(extractor) == 0) {
                ready.add(extractor);
            }
        }

        List<List<DataExtractor>> levels = new ArrayList<>();
        int processed = 0;
        while (!ready.isEmpty()) {
            List<DataExtractor> level = new ArrayList<>(ready);
            ready.clear();
            levels.add(List.copyOf(level));
            processed += level.size();

            for (DataExtractor extractor : level) {
                for (DataExtractor dependent : edges.get(extractor)) {
                    int remaining = inDegree.merge(dependent, -1, Integer::sum);
                    if (remaining == 0) {
                        ready.add(dependent);
                    }
                }
            }
        }

        if (processed != extractors.size()) {
            List<String> stuck = extractors.stream()
                    .filter(e -> inDegree.get(e) > 0)
                    .map(DataExtractor::name)
                    .toList();
            throw new IllegalStateException("Cyclic extractor dependencies among: " + stuck);
        }

        return levels;
    }

    /** Flattens {@link #levels(List)} back into a single deterministic ordering. */
    public static List<DataExtractor> topologicalOrder(List<DataExtractor> extractors) {
        List<DataExtractor> flat = new ArrayList<>();
        for (List<DataExtractor> level : levels(extractors)) {
            flat.addAll(level);
        }
        return List.copyOf(flat);
    }
}
