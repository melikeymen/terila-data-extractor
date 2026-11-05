package com.terila.extractor.util;

// Written by AI
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Collection helpers; every ordering helper sorts, because output must be deterministic. */
public final class CollectionUtil {

    private CollectionUtil() {
    }

    /** Sorts by the natural order of {@code key}'s string form. */
    public static <T> List<T> sortedByString(Iterable<T> values, Function<T, String> key) {
        List<T> copy = new ArrayList<>();
        values.forEach(copy::add);
        copy.sort(Comparator.comparing(value -> key.apply(value), Comparator.naturalOrder()));
        return copy;
    }

    /** Copies an iterable into a naturally sorted list. */
    public static <T extends Comparable<? super T>> List<T> sorted(Iterable<T> values) {
        List<T> copy = new ArrayList<>();
        values.forEach(copy::add);
        copy.sort(Comparator.naturalOrder());
        return copy;
    }

    /** Builds a map preserving insertion order from a keyed sequence. */
    public static <K, V> Map<K, V> toLinkedMap(Iterable<V> values, Function<V, K> key) {
        Map<K, V> map = new LinkedHashMap<>();
        for (V value : values) {
            map.put(key.apply(value), value);
        }
        return map;
    }

    /** Returns a new list containing {@code first} followed by {@code second}. */
    public static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    /** Whether an iterable is {@code null} or has no elements. */
    public static boolean isEmpty(Iterable<?> values) {
        return values == null || !values.iterator().hasNext();
    }
}
