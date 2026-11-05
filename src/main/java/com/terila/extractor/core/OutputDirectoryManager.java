package com.terila.extractor.core;

// Written by AI
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Owns the on-disk layout of the extraction output.
 *
 * <p>The layout is stable so the Terila server can rely on it:
 *
 * <pre>
 * extractor_output/
 *   _meta.json
 *   _errors.json
 *   blocks/ items/ entities/ recipes/ tags/ worldgen/ ...
 * </pre>
 */
public final class OutputDirectoryManager {

    /** Directory names, in the order they should be created. */
    public static final List<String> DIRECTORIES = List.of(
            "blocks",
            "blocks/blockstates",
            "items",
            "entities",
            "recipes",
            "tags",
            "worldgen",
            "enchantments",
            "damage_types",
            "trims",
            "packets",
            "translations",
            "commands",
            "advancements",
            "loot",
            "predicates",
            "item_modifiers",
            "registries",
            "registries/dump",
            "registries/nbt",
            "protocol",
            "structures",
            "structures/templates",
            "assets",
            "misc"
            // raw subdirectories are created lazily by RawNbtWriter and AtomicFileWriter, so a
            // wire-only run keeps exactly the same directory tree it had before raw mode existed.
    );

    public static final String META_FILE = "_meta.json";
    public static final String ERRORS_FILE = "_errors.json";
    public static final String VALIDATION_FILE = "_validation.json";
    public static final String CHANGELOG_FILE = "_changelog.json";

    private final JsonWriter writer;

    /** Creates a manager for the writer's output root. */
    public OutputDirectoryManager(JsonWriter writer) {
        this.writer = writer;
    }

    /** Absolute output root shared by the writer and the extractors. */
    public Path root() {
        return writer.outputRoot();
    }

    /** Creates the standard sub-directories. */
    public void prepare() {
        FileManager.ensureDirectory(root());
        for (String directory : DIRECTORIES) {
            FileManager.ensureDirectory(root().resolve(directory));
        }
    }

    /**
     * Top-level names this manager owns: the managed directory trees and the run-level reports.
     *
     * <p>Staging needs the names rather than the full {@link #DIRECTORIES} list because moving a
     * parent directory already moves its nested directories. The returned order is stable so a
     * publication step behaves deterministically.
     *
     * @return top-level names below the output root, without duplicates.
     */
    public static List<String> managedRootEntries() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (String directory : DIRECTORIES) {
            int slash = directory.indexOf('/');
            entries.add(slash < 0 ? directory : directory.substring(0, slash));
        }
        entries.add(META_FILE);
        entries.add(ERRORS_FILE);
        entries.add(VALIDATION_FILE);
        entries.add(CHANGELOG_FILE);
        return List.copyOf(entries);
    }

    /**
     * Removes every document from a previous run: the managed directories and the run-level
     * reports, including the validation report. Files outside the managed layout are left alone.
     *
     * @param keepMeta whether {@code _meta.json} should be preserved so the previous run can be
     *                 diffed against the new one.
     */
    public void clean(boolean keepMeta) {
        Path meta = root().resolve(META_FILE);
        String previousMeta = null;
        if (keepMeta && meta.toFile().isFile()) {
            try {
                previousMeta = java.nio.file.Files.readString(meta);
            } catch (java.io.IOException ignored) {
                previousMeta = null;
            }
        }

        for (String directory : DIRECTORIES) {
            FileManager.deleteRecursively(root().resolve(directory));
        }
        FileManager.deleteRecursively(root().resolve(ERRORS_FILE));
        FileManager.deleteRecursively(root().resolve(VALIDATION_FILE));
        FileManager.deleteRecursively(root().resolve(CHANGELOG_FILE));
        if (!keepMeta) {
            FileManager.deleteRecursively(meta);
        } else if (previousMeta != null) {
            try {
                java.nio.file.Files.writeString(meta, previousMeta);
            } catch (java.io.IOException ignored) {
                // Losing the previous meta is not fatal.
            }
        }
        prepare();
    }

    /** The writer that owns the output root. */
    public JsonWriter writer() {
        return writer;
    }
}

