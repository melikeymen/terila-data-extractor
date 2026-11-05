package com.terila.extractor.extractors.functions;

// Written by AI
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds cycles in the function call graph.
 *
 * <p>A function may call another function directly, through a function tag, or itself. Recursion is
 * legal in Minecraft but eventually hits the command depth limit, and a cycle that is not obvious
 * from a single source file is easy to introduce. This class reports strongly connected components:
 * every component with more than one function is a group that can reach itself, and a component of
 * one with a self edge is a direct recursive call.
 *
 * <p>Tarjan's algorithm is used instead of a simple path search because the number of distinct
 * cycles in a dense graph can grow exponentially; components are small, deterministic summaries.
 */
final class FunctionCallGraph {

    /**
     * One recursive group.
     *
     * @param functions functions in the component, sorted by identifier.
     * @param selfCall  whether the component is a single function that calls itself.
     */
    record Cycle(List<String> functions, boolean selfCall) {
    }

    private FunctionCallGraph() {
    }

    /**
     * Finds every recursive component of an adjacency map.
     *
     * @param adjacency function ids mapped to the functions they call. Unknown targets are ignored
     *                  as graph nodes; the caller reports them as missing references instead.
     * @return cycles sorted by their first function.
     */
    static List<Cycle> find(Map<String, ? extends Set<String>> adjacency) {
        return new Tarjan(adjacency).run();
    }

    private static final class Tarjan {

        private final Map<String, ? extends Set<String>> adjacency;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowlink = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<Cycle> cycles = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<String, ? extends Set<String>> adjacency) {
            this.adjacency = adjacency;
        }

        private List<Cycle> run() {
            Set<String> nodes = new TreeSet<>(adjacency.keySet());
            for (String node : nodes) {
                if (!index.containsKey(node)) {
                    connect(node);
                }
            }
            cycles.sort(Comparator.comparing(cycle -> cycle.functions().get(0)));
            return List.copyOf(cycles);
        }

        private void connect(String node) {
            index.put(node, nextIndex);
            lowlink.put(node, nextIndex);
            nextIndex++;
            stack.push(node);
            onStack.add(node);

            for (String neighbour : neighbours(node)) {
                if (!index.containsKey(neighbour)) {
                    connect(neighbour);
                    lowlink.put(node, Math.min(lowlink.get(node), lowlink.get(neighbour)));
                } else if (onStack.contains(neighbour)) {
                    lowlink.put(node, Math.min(lowlink.get(node), index.get(neighbour)));
                }
            }

            if (!lowlink.get(node).equals(index.get(node))) {
                return;
            }

            List<String> component = new ArrayList<>();
            String current;
            do {
                current = stack.pop();
                onStack.remove(current);
                component.add(current);
            } while (!current.equals(node));

            component.sort(Comparator.naturalOrder());
            boolean selfCall = component.size() == 1 && neighbours(node).contains(node);
            if (component.size() > 1 || selfCall) {
                cycles.add(new Cycle(List.copyOf(component), selfCall));
            }
        }

        private Set<String> neighbours(String node) {
            Set<String> adjacent = adjacency.get(node);
            return adjacent == null ? Set.of() : new TreeSet<>(adjacent);
        }
    }
}

