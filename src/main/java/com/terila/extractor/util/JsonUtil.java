package com.terila.extractor.util;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers for the extraction pipeline.
 *
 * <h2>Why codec output is canonicalised</h2>
 *
 * <p>The game encodes payloads through its own codecs, and several of those build JSON objects by
 * iterating a map that is keyed on <em>object identity</em> — most visibly the data component map of
 * an item stack, whose keys are {@code DataComponentType} instances and which therefore does not
 * override {@code hashCode}. Member order then follows the identity hash, which changes from one JVM
 * to the next: two byte-identical extraction runs produced different
 * {@code registries/dump/minecraft/loot_table.json} and {@code advancement.json} files, and different
 * item component orders, until the payloads were canonicalised.
 *
 * <p>Canonicalising sorts object members recursively ({@link JsonObject} keeps insertion order, so the
 * order is the output order) and leaves arrays alone: array order is part of the data — a loot
 * function list, a structure's block list, a tag's members — while object member order never is.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    /**
     * Returns a copy of {@code element} whose object members are sorted by name, recursively.
     *
     * <p>Strings, numbers, booleans and {@code null} are returned unchanged, and array element order
     * is preserved.
     */
    public static JsonElement canonical(JsonElement element) {
        if (element == null || element.isJsonNull() || !(element.isJsonObject() || element.isJsonArray())) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray source = element.getAsJsonArray();
            JsonArray result = new JsonArray(source.size());
            source.forEach(item -> result.add(canonical(item)));
            return result;
        }
        JsonObject source = element.getAsJsonObject();
        List<String> names = new ArrayList<>(source.keySet());
        names.sort(Comparator.naturalOrder());
        JsonObject result = new JsonObject();
        for (String name : names) {
            result.add(name, canonical(source.get(name)));
        }
        return result;
    }

    /** Canonical view of every member of {@code object}, for building a normalised document. */
    public static JsonObject canonicalObject(Map<String, JsonElement> members) {
        JsonObject result = new JsonObject();
        List<String> names = new ArrayList<>(members.keySet());
        names.sort(Comparator.naturalOrder());
        names.forEach(name -> result.add(name, canonical(members.get(name))));
        return result;
    }
}
