package com.terila.extractor.core;

// Written by AI
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the input search order.
 *
 * <p>The bug this pins down is a real one: the client jar search contained a literal path from the
 * developer's machine, which worked for exactly one person. Two properties matter, and both are
 * checked here — an installation can redirect the search through the environment, and the codebase
 * contains no machine specific path at all.
 */
class InputLocatorTest {

    /** An environment with none of the variables set. */
    private static final Map<String, String> EMPTY = Map.of();

    /**
     * A path that names a user profile explicitly, e.g. a drive letter followed by {@code Users} and a
     * profile name.
     *
     * <p>Matched case-insensitively and with either separator, because that is how such a path is
     * written on Windows and how it survives a copy/paste.
     */
    private static final Pattern USER_PROFILE_PATH =
            Pattern.compile("(?i)[a-z]:[\\\\/]users[\\\\/][^\\\\/\\s\"']+");

    /**
     * This class defines the detector, so it is the one file that has to mention the shape it looks
     * for; everything else is scanned.
     */
    private static final String DETECTOR_FILE = "InputLocatorTest.java";

    @Test
    void extraDirectoriesAreEmptyByDefault() {
        assertTrue(InputLocator.extraDirectories(EMPTY).isEmpty());
        assertTrue(InputLocator.extraDirectories(Map.of(InputLocator.EXTRA_DIRS_ENV, "  ")).isEmpty());
    }

    @Test
    void extraDirectoriesAreSplitOnThePlatformSeparator() {
        String configured = String.join(File.pathSeparator, "/one", "/two");
        List<Path> directories = InputLocator.extraDirectories(Map.of(InputLocator.EXTRA_DIRS_ENV, configured));

        assertEquals(List.of(Path.of("/one"), Path.of("/two")), directories);
    }

    /** A named jar wins over every discovered location. */
    @Test
    void anExplicitClientJarComesFirst() {
        Path explicit = Path.of("/explicit/minecraft_client.jar");
        List<Path> candidates = InputLocator.clientJarCandidates(
                Map.of(InputLocator.CLIENT_JAR_ENV, explicit.toString()));

        assertEquals(explicit, candidates.get(0));
    }

    /** Extra directories are searched before the launcher install, so a custom setup wins. */
    @Test
    void extraDirectoriesAreSearchedBeforeTheLauncherInstall() {
        Path directory = Path.of("/custom/minecraft");
        List<Path> candidates = InputLocator.clientJarCandidates(
                Map.of(InputLocator.EXTRA_DIRS_ENV, directory.toString()));

        int extra = candidates.indexOf(
                directory.resolve("minecraft_client." + InputLocator.version() + ".jar"));
        int launcher = candidates.indexOf(InputLocator.home()
                .resolve(".minecraft/versions").resolve(InputLocator.version())
                .resolve(InputLocator.version() + ".jar"));

        assertTrue(extra >= 0, "the extra directory has to contribute a candidate");
        assertTrue(launcher >= 0, "the launcher install has to stay a candidate");
        assertTrue(extra < launcher, "extra directories must be searched first");
    }

    @Test
    void theProjectLocalCacheIsAlwaysSearched() {
        List<Path> candidates = InputLocator.clientJarCandidates(EMPTY);
        Path expected = Path.of(InputLocator.CACHE_DIRECTORY,
                "minecraft_client." + InputLocator.version() + ".jar");

        assertTrue(candidates.contains(expected), "expected " + expected + " among " + candidates);
    }

    /**
     * Every absolute candidate has to be under the current user's home directory.
     *
     * <p>The cache, the Gradle artifact store and the launcher install all live there, so this is the
     * complete set of legitimate roots. A path from another machine's profile fails it.
     */
    @Test
    void everyAbsoluteCandidateLivesUnderTheUserHome() {
        Path home = InputLocator.home().toAbsolutePath().normalize();

        for (Path candidate : InputLocator.clientJarCandidates(EMPTY)) {
            if (!candidate.isAbsolute()) {
                continue;
            }
            assertTrue(candidate.normalize().startsWith(home),
                    "absolute candidate outside the user's home: " + candidate);
        }
    }

    /** The reports search covers the Gradle task's output layout. */
    @Test
    void reportsSearchCoversTheBuildLayout() {
        List<Path> candidates = InputLocator.reportsCandidates(EMPTY);

        assertTrue(candidates.contains(Path.of("build", "datagen")));
        assertTrue(candidates.contains(Path.of("build", "datagen", "reports")));
        assertTrue(candidates.contains(Path.of("datagen", InputLocator.version())));

        Path explicit = Path.of("/reports");
        assertEquals(explicit, InputLocator.reportsCandidates(
                Map.of(InputLocator.REPORTS_ENV, explicit.toString())).get(0));
    }

    /** Discovery must never throw, whatever the machine looks like. */
    @Test
    void discoveryNeverThrows() {
        InputLocator.reportsRoot();
        InputLocator.clientJar();
        assertEquals(InputLocator.version(),
                net.minecraft.SharedConstants.getCurrentVersion().name());
    }

    /**
     * No source file or build script may name a user profile directory.
     *
     * <p>This is the guard for the actual bug: the search order is allowed to consult the environment
     * and the current user's home, and nothing else. Scanning the tree means a path cannot creep back
     * in through a file the behavioural tests do not exercise.
     */
    @Test
    void noBuildFileOrSourceNamesAUserProfileDirectory() {
        List<String> offenders = new ArrayList<>();

        for (Path file : filesToScan()) {
            if (file.getFileName().toString().equals(DETECTOR_FILE)) {
                continue;
            }
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Failed to read " + file, unreadable);
            }
            if (USER_PROFILE_PATH.matcher(content).find()) {
                offenders.add(file.toString());
            }
        }

        assertTrue(offenders.isEmpty(),
                "these files hard-code a user directory; use " + InputLocator.EXTRA_DIRS_ENV
                        + " / terila.minecraft.dirs instead: " + offenders);
    }

    /** Sources and build files, i.e. everything a human maintains. */
    private static List<Path> filesToScan() {
        List<Path> files = new ArrayList<>();
        files.add(Path.of("build.gradle.kts"));
        files.add(Path.of("settings.gradle.kts"));
        files.add(Path.of("gradle.properties"));
        for (Path root : List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .forEach(files::add);
            } catch (IOException failure) {
                throw new UncheckedIOException("Failed to walk " + root, failure);
            }
        }
        return files;
    }
}
