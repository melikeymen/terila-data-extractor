package com.terila.extractor.core;

// Written by AI
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the cross-process exclusion contract of the output lock. */
class OutputLockTest {

    @TempDir
    Path temp;

    @Test
    void aSecondRunCannotLockTheSameOutput() throws Exception {
        Path output = temp.resolve("nested/out");

        try (OutputLock first = OutputLock.acquire(output)) {
            OutputLockedException failure = assertThrows(OutputLockedException.class,
                    () -> OutputLock.acquire(temp.resolve("nested/./out")));

            assertTrue(failure.getMessage().contains("already writing"));
            assertTrue(failure.getMessage().contains("pid=" + ProcessHandle.current().pid()));
            assertEquals(first.outputRoot(), failure.outputRoot());
            assertEquals(first.lockFile(), failure.lockFile());
        }
    }

    @Test
    void releasingTheLockLetsTheNextRunStartAndKeepsTheLockFile() throws Exception {
        Path output = temp.resolve("out");
        Path lockFile;
        try (OutputLock lock = OutputLock.acquire(output)) {
            lockFile = lock.lockFile();
            assertEquals(output.toAbsolutePath().normalize().getParent(), lockFile.getParent());
            assertTrue(Files.exists(lockFile));
        }

        assertTrue(Files.exists(lockFile),
                "the lock file must stay in place so every run locks the same file");
        try (OutputLock again = OutputLock.acquire(output)) {
            assertEquals(lockFile, again.lockFile());
        }
    }

    @Test
    void differentOutputsCanBeLockedAtTheSameTime() throws Exception {
        try (OutputLock first = OutputLock.acquire(temp.resolve("first"));
             OutputLock second = OutputLock.acquire(temp.resolve("second"))) {
            assertNotEquals(first.lockFile(), second.lockFile());
        }
    }

    @Test
    void recordsTheOwningProcessBesideTheLockFile() throws Exception {
        Path output = temp.resolve("out");
        Path ownerFile;

        try (OutputLock lock = OutputLock.acquire(output)) {
            ownerFile = lock.ownerFile();
            assertEquals(lock.lockFile().resolveSibling(lock.lockFile().getFileName() + ".owner"),
                    ownerFile);

            String owner = Files.readString(ownerFile, StandardCharsets.UTF_8);
            assertTrue(owner.contains("pid=" + ProcessHandle.current().pid()));
            assertTrue(owner.contains("started="));
        }

        Files.writeString(ownerFile, "stale owner\n", StandardCharsets.UTF_8);
        try (OutputLock lock = OutputLock.acquire(output)) {
            String owner = Files.readString(lock.ownerFile(), StandardCharsets.UTF_8);
            assertTrue(owner.contains("pid=" + ProcessHandle.current().pid()));
            assertFalse(owner.contains("stale owner"));
            try (var files = Files.list(ownerFile.getParent())) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                        "owner writes must not leave temporary files");
            }
        }
    }

    @Test
    void anotherJvmCannotLockTheOutputAndSeesTheOwner() throws Exception {
        Path output = temp.resolve("out");

        try (OutputLock lock = OutputLock.acquire(output)) {
            Process child = new ProcessBuilder(
                    javaExecutable().toString(),
                    "-cp", System.getProperty("java.class.path"),
                    LockProbe.class.getName(), output.toString())
                    .redirectErrorStream(true)
                    .start();

            boolean finished = child.waitFor(20, TimeUnit.SECONDS);
            String childOutput = new String(child.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!finished) {
                child.destroyForcibly();
            }

            assertTrue(finished, "the child JVM did not finish: " + childOutput);
            assertEquals(0, child.exitValue(), childOutput);
            assertTrue(childOutput.contains("LOCKED"), childOutput);
            assertTrue(childOutput.contains("pid=" + ProcessHandle.current().pid()), childOutput);
        }
    }

    @Test
    void waitsForAnotherRunToReleaseTheLock() throws Exception {
        Path output = temp.resolve("out");
        OutputLock first = OutputLock.acquire(output);
        try {
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(250L);
                    first.close();
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            }, "output-lock-releaser");
            releaser.start();

            try (OutputLock waited = OutputLock.acquire(output, Duration.ofSeconds(2))) {
                assertEquals(first.lockFile(), waited.lockFile());
            }

            releaser.join(2_000L);
            assertFalse(releaser.isAlive(), "the releaser thread must finish");
        } finally {
            first.close();
        }
    }

    @Test
    void timesOutWhenTheLockIsNotReleased() throws Exception {
        Path output = temp.resolve("out");

        try (OutputLock first = OutputLock.acquire(output)) {
            assertThrows(OutputLockedException.class,
                    () -> OutputLock.acquire(output, Duration.ofMillis(150L)));
        }
    }

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }
}

