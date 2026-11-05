package com.terila.extractor.core;

// Written by AI
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the staging contract independently of the extractors. */
class OutputTransactionTest {

    @TempDir
    Path temp;

    @Test
    void closingWithoutCommitKeepsThePreviousOutput() throws IOException {
        Path output = temp.resolve("out");
        write(output.resolve("blocks/old.json"), "{\"old\":true}");
        write(output.resolve("_meta.json"), "{\"old\":true}");
        write(output.resolve("notes.txt"), "keep");

        Path staging;
        try (OutputTransaction transaction = OutputTransaction.begin(output)) {
            staging = transaction.stagingRoot();
            write(staging.resolve("blocks/new.json"), "{\"new\":true}");

            assertFalse(Files.exists(output.resolve("blocks/new.json")),
                    "the staged document must not be visible before commit");
        }

        assertTrue(Files.exists(output.resolve("blocks/old.json")));
        assertTrue(Files.exists(output.resolve("_meta.json")));
        assertTrue(Files.exists(output.resolve("notes.txt")));
        assertFalse(Files.exists(staging), "closing must remove the staging directory");
    }

    @Test
    void commitReplacesTheManagedTreeAndPreservesUnrelatedFiles() throws IOException {
        Path output = temp.resolve("out");
        write(output.resolve("blocks/old.json"), "{\"old\":true}");
        write(output.resolve("_meta.json"), "{\"old\":true}");
        write(output.resolve("notes.txt"), "keep");

        Path staging;
        try (OutputTransaction transaction = OutputTransaction.begin(output)) {
            staging = transaction.stagingRoot();
            write(staging.resolve("blocks/new.json"), "{\"new\":true}");
            write(staging.resolve("_meta.json"), "{\"new\":true}");
            transaction.commit();
        }

        assertTrue(Files.exists(output.resolve("blocks/new.json")));
        assertFalse(Files.exists(output.resolve("blocks/old.json")),
                "the previous managed tree must not survive the publication");
        assertTrue(Files.readString(output.resolve("_meta.json"), StandardCharsets.UTF_8)
                .contains("\"new\":true"));
        assertTrue(Files.exists(output.resolve("notes.txt")),
                "a root file outside the managed layout must survive");
        assertFalse(Files.exists(staging), "a committed staging directory must be removed");
        try (Stream<Path> siblings = Files.list(temp)) {
            assertFalse(siblings.anyMatch(path -> path.getFileName().toString()
                            .startsWith(".terila-extractor-backup-")),
                    "the backup directory must be removed after a successful publication");
        }
    }

    @Test
    void commitCreatesANewOutputAtomically() throws IOException {
        Path output = temp.resolve("new/out");
        Path staging;
        try (OutputTransaction transaction = OutputTransaction.begin(output)) {
            staging = transaction.stagingRoot();
            assertTrue(Files.isDirectory(staging));
            write(staging.resolve("blocks/new.json"), "{\"new\":true}");
            transaction.commit();
        }

        assertTrue(Files.isDirectory(output.resolve("blocks")));
        assertTrue(Files.exists(output.resolve("blocks/new.json")));
        assertFalse(Files.exists(staging));
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}

