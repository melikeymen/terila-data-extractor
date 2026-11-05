package com.terila.extractor.core;

// Written by AI
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.ServerFunctionManager;

import java.util.List;

/**
 * The effective function tags a server has loaded.
 *
 * <p>The raw files in {@code data/<namespace>/tags/function} are not the whole story: the game
 * merges entries from stacked packs, applies a tag's {@code replace} flag and flattens nested
 * {@code #tag} references. A file-by-file reader cannot reproduce that behaviour, so the extractor
 * asks the same runtime that executes functions.
 *
 * <p>Both entry points provide an implementation: the head-less loader wraps
 * {@link ServerFunctionLibrary}, while the in-game entry point wraps {@link ServerFunctionManager}.
 */
public interface FunctionTagSource {

    /** Every effective tag identifier, in no particular order. */
    Iterable<Identifier> tagIds();

    /** Functions in {@code tag}, already merged, flattened and in execution order. */
    List<Identifier> functions(Identifier tag);

    /** Number of loaded function sources, including functions not referenced by any tag. */
    int functionCount();

    /** Adapts the head-less server resource's function library. */
    static FunctionTagSource of(ServerFunctionLibrary library) {
        return new FunctionTagSource() {
            @Override
            public Iterable<Identifier> tagIds() {
                return library.getAvailableTags();
            }

            @Override
            public List<Identifier> functions(Identifier tag) {
                return library.getTag(tag).stream().map(CommandFunction::id).toList();
            }

            @Override
            public int functionCount() {
                return library.getFunctions().size();
            }
        };
    }

    /** Adapts a running server's function manager. */
    static FunctionTagSource of(ServerFunctionManager manager) {
        return new FunctionTagSource() {
            @Override
            public Iterable<Identifier> tagIds() {
                return manager.getTagNames();
            }

            @Override
            public List<Identifier> functions(Identifier tag) {
                return manager.getTag(tag).stream().map(CommandFunction::id).toList();
            }

            @Override
            public int functionCount() {
                int count = 0;
                for (Identifier ignored : manager.getFunctionNames()) {
                    count++;
                }
                return count;
            }
        };
    }
}

