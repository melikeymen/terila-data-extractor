import groovy.json.JsonSlurper
import org.gradle.api.tasks.JavaExec
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    `java-library`
}

/*
 * Minecraft 26.x ships *deobfuscated* (Mojang names, no obfuscation layer), and Mojang no longer
 * publishes official mappings for it. Fabric Loom therefore cannot be used for this project:
 * there is nothing to remap. Instead the build compiles directly against the official server
 * bundler, which already contains the named server jar plus all of its runtime libraries.
 */

val minecraftVersion = property("minecraft_version") as String
val fabricLoaderVersion = property("fabric_loader_version") as String
val javaVersion = property("java_version") as String

version = "1.0.0"
group = "com.terila"

base {
    archivesName = "terila-extractor"
}

repositories {
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    mavenCentral()
}

// ---------------------------------------------------------------------------------------------
// Minecraft runtime (downloaded from the official version manifest, then unpacked)
// ---------------------------------------------------------------------------------------------

val home: String = System.getProperty("user.home")

/** Project local cache so a clean checkout can bootstrap itself with no manual downloads. */
val mcCacheDir = File(projectDir, ".mc-cache")
val cachedBundler = File(mcCacheDir, "minecraft_server.$minecraftVersion.jar")
val cachedClientJar = File(mcCacheDir, "minecraft_client.$minecraftVersion.jar")

/**
 * Every locale the game ships, unpacked from Mojang's asset index.
 *
 * The client jar only carries {@code en_us.json} — the other 140-odd languages are launcher assets —
 * so they are fetched from the official object store instead (SHA-1 verified, exactly like the jars).
 */
val languagesDir = File(mcCacheDir, "lang")
val cachedAssetIndex = File(mcCacheDir, "assets-$minecraftVersion.json")

/**
 * Locations a bundler may already live in, so an existing launcher install is reused instead of
 * downloading a second copy.
 *
 * Machine specific directories are described by the `terila.minecraft.dirs` Gradle property or the
 * `TERILA_MINECRAFT_DIRS` environment variable (a path-separated list), never by a literal path in
 * this file: the build has to work on any checkout, not just the author's machine.
 */
val extraMinecraftDirs: List<File> =
    (providers.gradleProperty("terila.minecraft.dirs").orNull
        ?: System.getenv("TERILA_MINECRAFT_DIRS")
        ?: "")
        .split(File.pathSeparatorChar)
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .map(::File)

val externalBundlerCandidates: List<File> = extraMinecraftDirs.map {
    File(it, "minecraft_server.$minecraftVersion.jar")
} + listOf(
    File(home, ".gradle/caches/neoformruntime/artifacts/minecraft_${minecraftVersion}_server.jar"),
    File(home, ".minecraft/versions/$minecraftVersion/${minecraftVersion}.jar")
)

/** A client jar may likewise already live in one of the extra directories. */
val externalClientJarCandidates: List<File> = extraMinecraftDirs.map {
    File(it, "minecraft_client.$minecraftVersion.jar")
} + listOf(
    cachedClientJar,
    File(home, ".gradle/caches/neoformruntime/artifacts/minecraft_${minecraftVersion}_client.jar")
)

val explicitBundler: String? = providers.gradleProperty("terila.minecraft.bundlerJar").orNull

val explicitClientJar: String? = providers.gradleProperty("terila.minecraft.clientJar").orNull

/** The client jar the extractor is pointed at: an explicit path, an existing install, else the cache. */
val resolvedClientJar: File = when {
    explicitClientJar != null -> File(explicitClientJar).also {
        require(it.isFile) { "terila.minecraft.clientJar points at a missing file: $it" }
    }
    else -> externalClientJarCandidates.firstOrNull { it.isFile } ?: cachedClientJar
}

val bundlerJar: File = when {
    explicitBundler != null -> File(explicitBundler).also {
        require(it.isFile) { "terila.minecraft.bundlerJar points at a missing file: $it" }
    }
    else -> externalBundlerCandidates.firstOrNull { it.isFile } ?: cachedBundler
}

// --- tiny HTTP helpers used by the download task ---------------------------------------------

fun httpText(url: String): String =
    URI(url).toURL().openConnection().apply {
        connectTimeout = 20_000
        readTimeout = 120_000
        setRequestProperty("User-Agent", "terila-extractor-gradle")
    }.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }

fun httpDownload(url: String, target: File) {
    target.parentFile.mkdirs()
    val partial = File(target.parentFile, target.name + ".part")
    val connection = URI(url).toURL().openConnection().apply {
        connectTimeout = 20_000
        readTimeout = 600_000
        setRequestProperty("User-Agent", "terila-extractor-gradle")
    }
    connection.getInputStream().use { input ->
        partial.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
    }
    if (!partial.renameTo(target)) {
        partial.copyTo(target, overwrite = true)
        partial.delete()
    }
}

fun sha1Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Resolves one of the {@code downloads} entries ({@code server}, {@code client}, ...) for the target version. */
data class ManifestDownload(val url: String, val sha1: String, val size: Long)

/** The version JSON of the target version, resolved through the official version manifest. */
@Suppress("UNCHECKED_CAST")
fun versionJson(): Map<String, Any?> {
    val slurper = JsonSlurper()
    val manifest = slurper.parseText(
        httpText("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    ) as Map<String, Any?>
    val versions = manifest["versions"] as List<Map<String, Any?>>
    val entry = versions.firstOrNull { it["id"] == minecraftVersion }
        ?: error(
            "Minecraft $minecraftVersion is not in the official version manifest yet " +
                "(newest is ${versions.firstOrNull()?.get("id")})."
        )
    return slurper.parseText(httpText(entry["url"] as String)) as Map<String, Any?>
}

/** Resolves one of the version's asset index (the launcher asset listing, language files included). */
@Suppress("UNCHECKED_CAST")
fun resolveAssetIndex(): ManifestDownload {
    val index = versionJson()["assetIndex"] as Map<String, Any?>
    return ManifestDownload(
        index["url"] as String,
        index["sha1"] as String,
        (index["size"] as Number).toLong()
    )
}

@Suppress("UNCHECKED_CAST")
fun resolveDownload(kind: String): ManifestDownload {
    val downloads = versionJson()["downloads"] as Map<String, Map<String, Any?>>
    val download = downloads[kind]
        ?: error("Minecraft $minecraftVersion has no '$kind' download in the version manifest.")
    return ManifestDownload(
        download["url"] as String,
        download["sha1"] as String,
        (download["size"] as Number).toLong()
    )
}

/** Downloads the bundler and/or the client jar into {@code .mc-cache} when they are not present. */
val downloadMinecraft = tasks.register("downloadMinecraft") {
    group = "terila"
    description = "Downloads the Minecraft $minecraftVersion server bundler and client jar into .mc-cache."
    outputs.files(cachedBundler, cachedClientJar)

    doLast {
        if (!bundlerJar.isFile) {
            val server = resolveDownload("server")
            logger.lifecycle("downloading Minecraft $minecraftVersion server (${server.size / 1_048_576} MiB)...")
            httpDownload(server.url, cachedBundler)
            val actual = sha1Of(cachedBundler)
            check(actual == server.sha1) {
                "server jar checksum mismatch: expected ${server.sha1}, got $actual"
            }
            logger.lifecycle("server bundler -> ${cachedBundler.absolutePath}")
        }
        if (!resolvedClientJar.isFile) {
            val client = resolveDownload("client")
            logger.lifecycle("downloading Minecraft $minecraftVersion client (${client.size / 1_048_576} MiB)...")
            httpDownload(client.url, cachedClientJar)
            val actual = sha1Of(cachedClientJar)
            check(actual == client.sha1) {
                "client jar checksum mismatch: expected ${client.sha1}, got $actual"
            }
            logger.lifecycle("client jar     -> ${cachedClientJar.absolutePath}")
        }
    }
}

/**
 * Downloads every language file the version ships into {@code .mc-cache/lang}.
 *
 *   ./gradlew downloadLanguages
 *
 * Minecraft keeps only {@code en_us.json} inside the client jar; the other locales are launcher
 * assets, listed in the version's asset index. Each file is verified against the index's SHA-1, so
 * the extraction input is reproducible.
 */
val downloadLanguages = tasks.register("downloadLanguages") {
    group = "terila"
    description = "Downloads every Minecraft $minecraftVersion language file into .mc-cache/lang."
    outputs.dir(languagesDir)

    doLast {
        val slurper = JsonSlurper()
        if (!cachedAssetIndex.isFile) {
            val index = resolveAssetIndex()
            logger.lifecycle("downloading the $minecraftVersion asset index...")
            httpDownload(index.url, cachedAssetIndex)
            val actual = sha1Of(cachedAssetIndex)
            check(actual == index.sha1) {
                "asset index checksum mismatch: expected ${index.sha1}, got $actual"
            }
        }

        @Suppress("UNCHECKED_CAST")
        val index = slurper.parseText(cachedAssetIndex.readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val objects = index["objects"] as Map<String, Map<String, Any?>>

        languagesDir.mkdirs()
        var written = 0
        var reused = 0
        objects.entries
            .filter { (path, _) -> isLanguageFile(path) }
            .sortedBy { it.key }
            .forEach { (path, object_) ->
                val locale = path.substringAfter("minecraft/lang/").removeSuffix(".json")
                val target = File(languagesDir, "$locale.json")
                val hash = object_["hash"] as String
                if (target.isFile && sha1Of(target) == hash) {
                    reused++
                    return@forEach
                }
                httpDownload(
                    "https://resources.download.minecraft.net/${hash.take(2)}/$hash",
                    target
                )
                val actual = sha1Of(target)
                check(actual == hash) { "language file $locale checksum mismatch: expected $hash, got $actual" }
                written++
            }
        logger.lifecycle("languages -> ${languagesDir.absolutePath} ($written downloaded, $reused cached)")
    }
}

/** Only real locale files: skip the root listing and the {@code deprecated.json} migration file. */
fun isLanguageFile(path: String): Boolean =
    path.startsWith("minecraft/lang/") && path.endsWith(".json") &&
        !path.endsWith("deprecated.json") &&
        path.removePrefix("minecraft/lang/").removeSuffix(".json").isNotEmpty()

val minecraftRuntimeDir = layout.buildDirectory.dir("minecraft-$minecraftVersion")

/** Unpacks the bundler so the named server jar and its libraries can go on the classpath. */
val extractMinecraft = tasks.register<Copy>("extractMinecraft") {
    group = "terila"
    description = "Unpacks the Minecraft $minecraftVersion server bundler (named jar + libraries)."
    dependsOn(downloadMinecraft)
    from(zipTree(bundlerJar))
    include("version.json")
    include("META-INF/versions/**")
    include("META-INF/libraries/**")
    into(minecraftRuntimeDir)
}

val mcServerJar: Provider<File> =
    minecraftRuntimeDir.map { it.file("META-INF/versions/$minecraftVersion/server-$minecraftVersion.jar").asFile }

val mcLibraries: Provider<FileTree> =
    minecraftRuntimeDir.map { dir -> dir.asFileTree.matching { include("META-INF/libraries/**/*.jar") } }

/** Root of the data generator's output: {@code reports/} plus a full vanilla data pack. */
val datagenDir: Provider<Directory> = layout.buildDirectory.dir("datagen")

// ---------------------------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------------------------

dependencies {
    // The game itself (compile + run).
    compileOnly(files(mcServerJar))
    compileOnly(mcLibraries)

    // Fabric entrypoints (`ModInitializer`) and the Mixin annotations. Provided by the launcher
    // at runtime, so these are not bundled into the mod jar.
    compileOnly("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    compileOnly("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7")

    api("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Tests read real Minecraft data, so the game runtime has to be on the test classpath too.
    testImplementation(files(mcServerJar))
    testImplementation(mcLibraries)
}

java {
    withSourcesJar()
}

// The tag registry whitelist is versioned data, not code: bundle it at the same relative path so a
// packaged run finds the configuration a checkout has.
tasks.processResources {
    from("config") {
        into("config")
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(extractMinecraft)
    options.encoding = "UTF-8"
    options.release = javaVersion.toInt()
}

/*
 * `./gradlew javadoc` has to be clean, so every doclint check stays on except `missing`.
 *
 * The `missing` group is the check that demands a `@param`/`@return` tag on *every* member, including
 * one-line accessors and no-argument constructors whose names already say everything. This code base
 * documents behaviour instead: parameters and return values get tags where they carry information
 * (what a value means, what a failure is), prose everywhere else. Everything else — broken `{@link}`
 * references, malformed HTML, syntax errors in the comments themselves — still fails the task.
 */
tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all,-missing", true)
        // The site is meant to look like the documentation of the era the code targets: keep the
        // stock page structure, then layer docs/javadoc.css over it. The overview page is the
        // hand-written front door, and a build stamp would make the output non-reproducible.
        addBooleanOption("notimestamp", true)
        // The java launcher in front of javadoc only knows the double-dashed form, so the option
        // is written with its own dash: Gradle prefixes one and javadoc sees "--add-stylesheet".
        addStringOption("-add-stylesheet", file("docs/javadoc.css").absolutePath)
        // The documentation is written in English; without this javadoc stamps the machine locale
        // into lang="..." on every page.
        locale = "en_US"
        windowTitle = "Terila Data Extractor 26.3 - API Reference"
        docTitle = "Terila Data Extractor 26.3"
        overview = file("docs/overview.html").absolutePath
        bottom = "<font size=\"-1\">Terila Data Extractor 26.3 &middot; protocol 777 &middot; hand-written Javadoc</font>"
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(extractMinecraft)
    // Tests that load the vanilla data set need the extracted runtime on the classpath.
    classpath += files(mcServerJar, mcLibraries)
    minHeapSize = "1g"
    maxHeapSize = "3g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // `-Dterila.golden.update=true` (and any future terila.* switch) has to reach the test JVM.
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("terila.")) {
            systemProperty(name, value.toString())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Head-less extraction
// ---------------------------------------------------------------------------------------------

/** Runtime classpath for the standalone tool: the module plus the whole Minecraft runtime. */
val mcRuntimeClasspath: FileCollection = files(
    sourceSets["main"].output,
    configurations.runtimeClasspath,
    mcServerJar,
    mcLibraries
)

/**
 * Runs Minecraft's own data generator. This is the only way to reach data that no runtime API
 * exposes — packet ids, block state ids, registry protocol ids and the command tree in report form:
 *
 *   ./gradlew runDataGenerator
 *
 * Output lands in build/datagen (reports/ + a full vanilla data pack). The extractor picks the
 * reports up automatically.
 */
tasks.register<JavaExec>("runDataGenerator") {
    group = "terila"
    description = "Generates Minecraft $minecraftVersion reports (packets, blocks, registries, commands)."
    dependsOn(extractMinecraft)
    mainClass.set("net.minecraft.data.Main")
    classpath = mcRuntimeClasspath
    val output = datagenDir.get().asFile
    args(
        "--all",
        "--reports",
        "--output", output.absolutePath
    )
    // Cheap to skip: the reports only change when the Minecraft version changes.
    inputs.property("minecraftVersion", minecraftVersion)
    outputs.dir(output)
    doFirst {
        output.mkdirs()
    }
}

/**
 * Runs the full extraction pipeline head-less, without launching a game:
 *
 *   ./gradlew runExtractor --args="--output build/extractor_output"
 *
 * The extractor auto-discovers the data generator reports and the client jar in .mc-cache, so a
 * plain run already covers every data category.
 */
tasks.register<JavaExec>("runExtractor") {
    group = "terila"
    description = "Runs the extraction pipeline head-less against the Minecraft $minecraftVersion data."
    dependsOn(extractMinecraft)
    mustRunAfter("runDataGenerator")
    mainClass.set("com.terila.extractor.bootstrap.StandaloneExtractor")
    classpath = mcRuntimeClasspath
    // Defaults only: passing --args="..." on the command line replaces them entirely.
    args("--output", layout.buildDirectory.dir("extractor_output").get().asFile.absolutePath)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--client-jar", resolvedClientJar.absolutePath)
    })
}

/**
 * Data generator reports + extraction in one go; what CI should call.
 *
 * The extractor picks the reports up on its own, and {@code mustRunAfter} guarantees the generator
 * has finished before extraction starts (Gradle would otherwise be free to run them in parallel).
 */
tasks.register("extractAll") {
    group = "terila"
    description = "Generates reports with Minecraft's data generator, then runs the full extraction."
    // Every data set the extractor can consume, including the locales the client jar does not ship.
    dependsOn("runDataGenerator", "downloadLanguages", "runExtractor")
}

tasks.register("versions") {
    group = "terila"
    description = "Prints the Minecraft / protocol versions this extractor targets."
    val protocolVersion = property("minecraft_protocol_version")
    val dataPackVersion = property("data_pack_version")
    val worldVersion = property("minecraft_world_version")
    doLast {
        println("Minecraft      : $minecraftVersion")
        println("Protocol       : $protocolVersion")
        println("Data pack      : $dataPackVersion")
        println("World version  : $worldVersion")
        println("Fabric loader  : $fabricLoaderVersion")
        println("Server bundler : ${bundlerJar.absolutePath}")
        println("Client jar     : ${resolvedClientJar.absolutePath}")
        println("Languages      : ${languagesDir.absolutePath}${if (languagesDir.isDirectory) "" else " (not downloaded)"}")
        println("Extra dirs     : ${extraMinecraftDirs.joinToString(File.pathSeparator) { it.absolutePath }.ifEmpty { "(none)" }}")
    }
}
