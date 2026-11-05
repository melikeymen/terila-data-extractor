package com.terila.extractor.extractors.functions;

// Written by AI
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds function references in {@code .mcfunction} command bodies.
 *
 * <p>A function source is a list of commands, not a registry payload. Calls to other functions can
 * therefore only be found by reading the commands themselves. This parser recognises the command
 * forms that reference a function:
 *
 * <ul>
 *   <li>{@code function <id>} and {@code function #tag};</li>
 *   <li>{@code execute ... run function <id>} and nested execute chains;</li>
 *   <li>{@code execute if function <id>} and {@code unless function <id>};</li>
 *   <li>{@code return run function <id>};</li>
 *   <li>{@code schedule function <id> ...}.</li>
 * </ul>
 *
 * <p>Quoted arguments are tokenised as one token, so the word {@code function} inside a {@code say}
 * or {@code tellraw} argument is not mistaken for a call. Commands that use a macro such as
 * {@code function $(which)} are still recorded, but marked {@link Call#dynamic()}: their target
 * cannot be validated until the function runs.
 *
 * <p>This is deliberately a command-reference finder, not a full Brigadier parser. It follows the
 * execute grammar far enough to find the {@code run} that separates the command chain from the
 * command being run, which is what keeps call detection free of false positives.
 */
final class FunctionCallParser {

    /** What a command reference points at. */
    enum Kind {
        FUNCTION,
        TAG;

        /** Lower-case name used in the JSON output. */
        String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One reference found in a function body.
     *
     * @param target  canonical identifier, without the {@code #} tag prefix.
     * @param kind    whether the target is a function or a function tag.
     * @param line    1-based line in the source.
     * @param dynamic whether the target contains a macro and cannot be resolved statically.
     */
    record Call(String target, Kind kind, int line, boolean dynamic) {

        /** The reference as it appears in a missing-reference report. */
        String reference() {
            return kind == Kind.TAG ? "#" + target : target;
        }
    }

    private FunctionCallParser() {
    }

    /**
     * Parses a normalised function source.
     *
     * @param content command lines separated by {@code \n}.
     * @return every function reference, in source order.
     */
    static List<Call> parse(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        String[] lines = content.split("\n", -1);
        int limit = lines.length;
        if (content.endsWith("\n")) {
            limit--;
        }

        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("$")) {
                line = line.substring(1).stripLeading();
            }
            if (line.isEmpty()) {
                continue;
            }
            parseTokens(tokenize(line), i + 1, calls);
        }
        return List.copyOf(calls);
    }

    private static void parseTokens(List<String> tokens, int line, List<Call> calls) {
        if (tokens.isEmpty()) {
            return;
        }

        String head = tokens.get(0);
        if (head.equals("function")) {
            add(tokens, 1, line, calls);
            return;
        }
        if (head.equals("schedule") && tokens.size() > 1 && tokens.get(1).equals("function")) {
            add(tokens, 2, line, calls);
            return;
        }
        if (head.equals("return") && tokens.size() > 1 && tokens.get(1).equals("run")) {
            parseTokens(tokens.subList(2, tokens.size()), line, calls);
            return;
        }
        if (!head.equals("execute")) {
            return;
        }

        int run = indexOfRun(tokens);
        int conditionEnd = run < 0 ? tokens.size() : run;
        for (int i = 1; i < conditionEnd - 1; i++) {
            if ((tokens.get(i).equals("if") || tokens.get(i).equals("unless"))
                    && tokens.get(i + 1).equals("function")) {
                add(tokens, i + 2, line, calls);
            }
        }
        if (run >= 0) {
            parseTokens(tokens.subList(run + 1, tokens.size()), line, calls);
        }
    }

    /**
     * The first {@code run} subcommand of an execute chain.
     *
     * <p>Quoted arguments are already one token, so a {@code run} inside a JSON or text argument
     * cannot split the chain. The target of {@code if function} is skipped: a function literally
     * named {@code run} would otherwise be mistaken for the separator.
     */
    private static int indexOfRun(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("function") && i > 0
                    && (tokens.get(i - 1).equals("if") || tokens.get(i - 1).equals("unless"))) {
                i++;
                continue;
            }
            if (token.equals("run")) {
                return i;
            }
        }
        return -1;
    }

    private static void add(List<String> tokens, int index, int line, List<Call> calls) {
        if (index >= tokens.size()) {
            return;
        }

        String raw = tokens.get(index).trim();
        if (raw.isEmpty()) {
            return;
        }

        boolean tag = raw.startsWith("#");
        if (tag) {
            raw = raw.substring(1).trim();
        }
        if (raw.isEmpty()) {
            return;
        }

        boolean dynamic = raw.contains("$");
        if (!dynamic && !raw.contains(":")) {
            raw = "minecraft:" + raw;
        }
        calls.add(new Call(raw, tag ? Kind.TAG : Kind.FUNCTION, line, dynamic));
    }

    /**
     * Splits a command line into tokens, keeping quoted arguments together.
     *
     * <p>Minecraft's command syntax uses single or double quotes for arguments containing spaces.
     * The quote characters themselves are not part of the value.
     */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean tokenStarted = false;
        char quote = 0;

        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);

            if (quote != 0) {
                if (character == '\\' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else if (character == quote) {
                    quote = 0;
                } else {
                    current.append(character);
                }
                continue;
            }

            if (character == '"' || character == '\'') {
                quote = character;
                tokenStarted = true;
            } else if (Character.isWhitespace(character)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(character);
                tokenStarted = true;
            }
        }

        if (tokenStarted) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}

