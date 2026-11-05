package com.terila.extractor.core;

// Written by AI
import net.minecraft.SharedConstants;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the two inputs the running game has to produce for a complete extraction.
 *
 * <p>Neither is a game API:
 *
 * <ul>
 *   <li>the <strong>data generator reports</strong> ({@code packets.json}, {@code blocks.json},
 *       {@code registries.json}, the per-item component reports, ...) are written by
 *       {@code net.minecraft.data.Main};</li>
 *   <li>the <strong>client jar</strong> holds the resource pack and {@code en_us.json}, which the
 *       server side never sees.</li>
 * </ul>
 *
 * <p>Both are looked up in the same order everywhere — an explicit value, the environment, a system
 * property, the build output this project uses, then the machine's own launcher install — so the
 * standalone tool, the in-game mixin and the tests all find the same files without repeating the
 * search.
 *
 * <p>Nothing here is tied to a particular machine. An installation that keeps its Minecraft jars
 * somewhere unusual names the directory in {@link #EXTRA_DIRS_ENV} (the same variable the Gradle build
 * honours); the code contains no absolute path that belongs to a specific user. The candidate lists
 * are package visible and take their environment as a parameter so that property can be tested
 * rather than asserted in a comment.
 */
public final class InputLocator {

    public static final String REPORTS_ENV = "TERILA_REPORTS";
    public static final String CLIENT_JAR_ENV = "TERILA_CLIENT_JAR";
    public static final String REPORTS_PROPERTY = "terila.reports.dir";
    public static final String CLIENT_JAR_PROPERTY = "terila.minecraft.clientJar";
    public static final String LANGUAGES_ENV = "TERILA_LANGUAGES";
    public static final String LANGUAGES_PROPERTY = "terila.languages.dir";

    /**
     * Semicolon (Windows) or colon (elsewhere) separated list of extra directories searched for the
     * server bundler and the client jar.
     *
     * <p>This is how a machine that keeps its Minecraft jars outside the Gradle cache and outside
     * {@code .mc-cache} configures the tool without recompiling it. Set it once and every entrypoint
     * (Gradle build, head-less run, in-game mixin, tests) finds the files.
     */
    public static final String EXTRA_DIRS_ENV = "TERILA_MINECRAFT_DIRS";

    /** Relative path of the project-local download cache the Gradle build fills. */
    public static final String CACHE_DIRECTORY = ".mc-cache";

    private InputLocator() {
    }

    /** The data generator reports directory, when one can be found. */
    public static Optional<Path> reportsRoot() {
        return firstExisting(reportsCandidates(System.getenv()));
    }

    /** The client jar, when one can be found. */
    public static Optional<Path> clientJar() {
        return firstExisting(clientJarCandidates(System.getenv()));
    }

    /**
     * The directory holding every locale as {@code <locale>.json}, when one can be found.
     *
     * <p>The client jar only carries {@code en_us.json}, so this is the only source for the other
     * languages. {@code ./gradlew downloadLanguages} fills {@code .mc-cache/lang} from Mojang's asset
     * index; a launcher's own {@code assets/objects} cache holds the same files but content addressed,
     * which this tool deliberately does not read (the objects are unnamed and need the index anyway).
     */
    public static Optional<Path> languagesDir() {
        return firstExisting(languageCandidates(System.getenv()));
    }

    /** Directory name of the version currently being extracted, e.g. {@code 26.3}. */
    public static String version() {
        return SharedConstants.getCurrentVersion().name();
    }

    /**
     * Directories named by {@link #EXTRA_DIRS_ENV}, in order.
     *
     * <p>Empty when the variable is unset, which is the normal case: the build output and the launcher
     * install are enough on a machine that has never customised anything.
     */
    public static List<Path> extraDirectories() {
        return extraDirectories(System.getenv());
    }

    // --- candidates (package visible so the search order can be tested) ---------------------------

    static List<Path> reportsCandidates(Map<String, String> environment) {
        List<Path> candidates = new ArrayList<>();
        add(candidates, environment.get(REPORTS_ENV));
        add(candidates, System.getProperty(REPORTS_PROPERTY));
        // Layout produced by `./gradlew runDataGenerator`.
        candidates.add(Path.of("build", "datagen"));
        candidates.add(Path.of("build", "datagen", "reports"));
        candidates.add(Path.of("build", "reports"));
        // A server run directory, and the versioned cache used by other data tooling.
        candidates.add(Path.of("run", "reports"));
        candidates.add(Path.of("datagen", version()));
        return candidates;
    }

    static List<Path> clientJarCandidates(Map<String, String> environment) {
        List<Path> candidates = new ArrayList<>();
        add(candidates, environment.get(CLIENT_JAR_ENV));
        add(candidates, System.getProperty(CLIENT_JAR_PROPERTY));

        String version = version();
        candidates.add(Path.of(CACHE_DIRECTORY, "minecraft_client." + version + ".jar"));
        candidates.add(gradleCache().resolve("minecraft_" + version + "_client.jar"));
        // Machine specific locations come from the environment, never from a literal in this file.
        for (Path directory : extraDirectories(environment)) {
            candidates.add(directory.resolve("minecraft_client." + version + ".jar"));
            candidates.add(directory.resolve("minecraft_" + version + "_client.jar"));
        }
        candidates.add(home().resolve(".minecraft/versions").resolve(version).resolve(version + ".jar"));
        return candidates;
    }

    static List<Path> languageCandidates(Map<String, String> environment) {
        List<Path> candidates = new ArrayList<>();
        add(candidates, environment.get(LANGUAGES_ENV));
        add(candidates, System.getProperty(LANGUAGES_PROPERTY));
        candidates.add(Path.of(CACHE_DIRECTORY, "lang"));
        candidates.add(Path.of("build", "lang"));
        candidates.add(Path.of("run", "lang"));
        return candidates;
    }

    static List<Path> extraDirectories(Map<String, String> environment) {
        String configured = environment.get(EXTRA_DIRS_ENV);
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<Path> directories = new ArrayList<>();
        for (String entry : configured.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                directories.add(Path.of(entry.trim()));
            }
        }
        return directories;
    }

    /** The launcher/Gradle artifact cache, or the user's home directory when that is unset. */
    static Path gradleCache() {
        return home().resolve(".gradle/caches/neoformruntime/artifacts");
    }

    static Path home() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? Path.of(".") : Path.of(home);
    }

    private static void add(List<Path> candidates, String value) {
        if (value != null && !value.isBlank()) {
            candidates.add(Path.of(value));
        }
    }

    private static Optional<Path> firstExisting(List<Path> candidates) {
        return candidates.stream().filter(Files::exists).findFirst();
    }
}
