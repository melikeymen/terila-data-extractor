package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the function tag graph without booting Minecraft. */
class FunctionTagGraphTest {

    @Test
    void resolvesNestedTagsAndReportsDanglingReferences() {
        FunctionTagGraph graph = FunctionTagGraph.of(Map.of(
                "demo:load", json("{\"values\":[\"demo:tick\"]}"),
                "demo:all", json("{\"values\":[\"#demo:load\",\"demo:sub/nested\",\"demo:missing\","
                        + "{\"id\":\"#demo:missing_tag\",\"required\":false}]}")
        ), Set.of("demo:tick", "demo:sub/nested"));

        FunctionTagGraph.Tag all = graph.tag("demo:all");
        FunctionTagGraph.Resolution resolution = graph.resolve(all);

        assertEquals(List.of("demo:tick", "demo:sub/nested"), resolution.functions());
        assertEquals(1, resolution.missing().size());
        assertEquals("demo:missing", resolution.missing().get(0).id());
        assertEquals(FunctionTagGraph.Kind.FUNCTION, resolution.missing().get(0).kind());
        assertFalse(resolution.cyclic());

        assertTrue(graph.exists(all.references().get(0)));
        assertTrue(graph.exists(all.references().get(1)));
        assertFalse(graph.exists(all.references().get(2)));
        assertFalse(graph.exists(all.references().get(3)));
        assertFalse(all.references().get(3).required(),
                "an optional reference must stay optional after parsing");
    }

    @Test
    void defaultsMissingNamespacesToMinecraft() {
        FunctionTagGraph graph = FunctionTagGraph.of(Map.of(
                "demo:all", json("{\"values\":[\"tick\",\"#missing\"]}")
        ), Set.of("minecraft:tick"));

        FunctionTagGraph.Tag tag = graph.tag("demo:all");
        assertEquals("minecraft:tick", tag.references().get(0).id());
        assertEquals("minecraft:missing", tag.references().get(1).id());
        assertEquals(FunctionTagGraph.Kind.TAG, tag.references().get(1).kind());

        FunctionTagGraph.Resolution resolution = graph.resolve(tag);
        assertEquals(List.of("minecraft:tick"), resolution.functions());
        assertEquals(1, resolution.missing().size());
        assertEquals("minecraft:missing", resolution.missing().get(0).id());
    }

    @Test
    void detectsCyclesWithoutRecursingForever() {
        FunctionTagGraph graph = FunctionTagGraph.of(Map.of(
                "demo:a", json("{\"values\":[\"#demo:b\",\"demo:tick\"]}"),
                "demo:b", json("{\"values\":[\"#demo:a\"]}")
        ), Set.of("demo:tick"));

        FunctionTagGraph.Resolution a = graph.resolve(graph.tag("demo:a"));
        assertTrue(a.cyclic());
        assertEquals(List.of("demo:tick"), a.functions());
        assertTrue(a.cycles().contains("demo:b -> demo:a"), "cycles: " + a.cycles());

        FunctionTagGraph.Resolution b = graph.resolve(graph.tag("demo:b"));
        assertTrue(b.cyclic());
        assertEquals(List.of("demo:tick"), b.functions());
        assertTrue(b.cycles().contains("demo:a -> demo:b"), "cycles: " + b.cycles());
    }

    @Test
    void keepsTheRawValuesAndReplaceFlag() {
        FunctionTagGraph graph = FunctionTagGraph.of(Map.of(
                "demo:all", json("{\"replace\":true,\"values\":[\"demo:tick\"]}")
        ), Set.of("demo:tick"));

        FunctionTagGraph.Tag tag = graph.tag("demo:all");
        assertTrue(tag.replace());
        assertEquals("[\"demo:tick\"]", tag.values().toString());
    }

    private static JsonElement json(String value) {
        return JsonParser.parseString(value);
    }
}

