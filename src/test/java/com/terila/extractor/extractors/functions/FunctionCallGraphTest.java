package com.terila.extractor.extractors.functions;

// Written by AI
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests strongly connected component detection without loading a data pack. */
class FunctionCallGraphTest {

    @Test
    void findsMutualAndSelfCycles() {
        List<FunctionCallGraph.Cycle> cycles = FunctionCallGraph.find(Map.of(
                "demo:a", Set.of("demo:b"),
                "demo:b", Set.of("demo:a"),
                "demo:c", Set.of("demo:c"),
                "demo:d", Set.of()
        ));

        assertEquals(2, cycles.size());
        assertEquals(List.of("demo:a", "demo:b"), cycles.get(0).functions());
        assertFalse(cycles.get(0).selfCall());
        assertEquals(List.of("demo:c"), cycles.get(1).functions());
        assertTrue(cycles.get(1).selfCall());
    }

    @Test
    void returnsNothingForAnAcyclicGraph() {
        assertTrue(FunctionCallGraph.find(Map.of(
                "demo:a", Set.of("demo:b"),
                "demo:b", Set.of("demo:c"),
                "demo:c", Set.of()
        )).isEmpty());
    }

    @Test
    void mergesAThreeFunctionComponentIntoOneCycle() {
        List<FunctionCallGraph.Cycle> cycles = FunctionCallGraph.find(Map.of(
                "demo:a", Set.of("demo:b"),
                "demo:b", Set.of("demo:c"),
                "demo:c", Set.of("demo:a")
        ));

        assertEquals(1, cycles.size());
        assertEquals(List.of("demo:a", "demo:b", "demo:c"), cycles.get(0).functions());
        assertFalse(cycles.get(0).selfCall());
    }
}

