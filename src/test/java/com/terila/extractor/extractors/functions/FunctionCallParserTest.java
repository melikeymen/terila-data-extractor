package com.terila.extractor.extractors.functions;

// Written by AI
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests command-reference parsing without loading a data pack. */
class FunctionCallParserTest {

    @Test
    void findsDirectExecuteConditionAndScheduleCalls() {
        String content = """
                # a comment, not a call
                function demo:a
                execute as @a at @s run function demo:b
                execute if function demo:c run function #demo:tag
                execute unless function demo:d run function demo:e
                schedule function demo:f 1t append
                say "run function demo:not_a_call"
                $function $(dynamic)
                """;

        List<FunctionCallParser.Call> calls = FunctionCallParser.parse(content);

        assertEquals(List.of(
                "demo:a",
                "demo:b",
                "demo:c",
                "#demo:tag",
                "demo:d",
                "demo:e",
                "demo:f",
                "$(dynamic)"), calls.stream().map(FunctionCallParser.Call::reference).toList());
        assertEquals(List.of(2, 3, 4, 4, 5, 5, 6, 8),
                calls.stream().map(FunctionCallParser.Call::line).toList());

        assertEquals(FunctionCallParser.Kind.TAG, calls.get(3).kind());
        assertEquals("demo:tag", calls.get(3).target());

        FunctionCallParser.Call dynamic = calls.get(7);
        assertTrue(dynamic.dynamic());
        assertEquals("$(dynamic)", dynamic.target());
    }

    @Test
    void followsNestedExecuteChainsAndReturnCommands() {
        String content = """
                execute as @a run execute at @s run function demo:nested
                return run function demo:returned
                execute if function run run function demo:after
                """;

        List<FunctionCallParser.Call> calls = FunctionCallParser.parse(content);

        assertEquals(List.of("demo:nested", "demo:returned", "minecraft:run", "demo:after"),
                calls.stream().map(FunctionCallParser.Call::reference).toList());
        assertEquals(List.of(1, 2, 3, 3), calls.stream().map(FunctionCallParser.Call::line).toList());
    }

    @Test
    void ignoresTheWordFunctionInsideArguments() {
        String content = """
                say "function demo:not_a_call"
                execute as @a run say run function demo:also_not_a_call
                tellraw @a ["", "function ", "demo:not_a_call"]
                """;

        assertTrue(FunctionCallParser.parse(content).isEmpty());
    }

    @Test
    void keepsQuotedTargetsAndStripsTheTagMarker() {
        List<FunctionCallParser.Call> calls = FunctionCallParser.parse("""
                function "demo:with_space"
                function #tag_without_namespace
                """);

        assertEquals(2, calls.size());
        assertEquals("demo:with_space", calls.get(0).target());
        assertFalse(calls.get(0).dynamic());
        assertEquals("minecraft:tag_without_namespace", calls.get(1).target());
        assertEquals("#minecraft:tag_without_namespace", calls.get(1).reference());
    }

    @Test
    void skipsCommentsBlankLinesAndTrailingNewline() {
        assertTrue(FunctionCallParser.parse("# function demo:hidden\n\n   \n").isEmpty());
        assertTrue(FunctionCallParser.parse("").isEmpty());
    }
}

