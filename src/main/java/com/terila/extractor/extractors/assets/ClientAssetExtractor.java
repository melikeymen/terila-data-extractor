package com.terila.extractor.extractors.assets;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.AtomicFileWriter;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.util.HashUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes {@code assets/}: every resource pack file the client jar ships, plus an index of all of them.
 *
 * <p>The resource pack is the other half of the game's data. Models, block state definitions,
 * atlases, fonts, equipment layers, particle definitions, sound manifests and the waypoint styles
 * only exist as client assets; a renderer or a resource pack editor needs them, and they are not
 * reachable from the server side at all.
 *
 * <ul>
 *   <li>every JSON asset is mirrored verbatim under its original path, so the output can be used as a
 *       resource pack directory as-is;</li>
 *   <li>binary assets (textures, sounds, shaders) are always listed in {@code assets/index.json} with
 *       their size and CRC, and the content hashes are what makes the index usable even when the
 *       bytes are absent: {@code assets/manifest.json} groups them by hash so a consumer can verify
 *       or fetch the originals.</li>
 * </ul>
 *
 * <p>Copying the binary payload itself is opt-in ({@link ExtractorConfig#copyBinaryAssets()}): it
 * multiplies the output by several hundred megabytes, which a data-only consumer does not want, but
 * a renderer or a pack editor needs the textures and sounds on disk. With the flag on, the output
 * directory is a complete resource pack.
 */
public final class ClientAssetExtractor extends AbstractExtractor {

    private static final String PREFIX = "assets/minecraft/";

    public ClientAssetExtractor() {
        super("client_assets", DataType.ASSETS, "assets/index.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Path clientJar = context.clientJar().orElse(null);
        if (clientJar == null || !Files.isRegularFile(clientJar)) {
            context.errors().warning("CLIENT_JAR_UNAVAILABLE", name(), null,
                    "Resource pack assets only ship in the client jar; assets were skipped "
                            + "(pass --client-jar)");
            return 0;
        }

        boolean copyBinaries = context.config().copyBinaryAssets();

        JsonArray index = new JsonArray();
        JsonObject directoryCounts = new JsonObject();
        JsonObject binaryByType = new JsonObject();
        TreeSet<String> hashes = new TreeSet<>();
        long totalBytes = 0;
        long copiedBytes = 0;
        int copied = 0;
        int binaries = 0;
        int binaryFailures = 0;

        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith(PREFIX))
                    .sorted(java.util.Comparator.comparing(ZipEntry::getName))
                    .toList();

            for (ZipEntry entry : entries) {
                String relative = entry.getName().substring(PREFIX.length());
                JsonObject record = new JsonObject();
                record.addProperty("path", relative);
                record.addProperty("bytes", entry.getSize());
                record.addProperty("crc32", Long.toHexString(entry.getCrc()));
                totalBytes += Math.max(0, entry.getSize());

                if (entry.getName().endsWith(".json")) {
                    JsonElement parsed = readJson(zip, entry);
                    if (parsed == null) {
                        context.errors().warning("ASSET_NOT_JSON", name(), relative,
                                "Asset is not valid JSON and was not mirrored");
                    } else {
                        context.writer().write("assets/" + relative, parsed);
                        record.addProperty("kind", "json");
                        copied++;
                    }
                } else {
                    record.addProperty("kind", "binary");
                    binaries++;

                    // The CRC already identifies the file; a content hash is added so the index can
                    // be diffed against another Minecraft version and used to fetch the original.
                    byte[] bytes = null;
                    try {
                        bytes = readBytes(zip, entry);
                        record.addProperty("sha256", HashUtil.sha256(bytes));
                        hashes.add(record.get("sha256").getAsString());
                    } catch (IOException | RuntimeException unreadable) {
                        binaryFailures++;
                        context.errors().warning("ASSET_UNREADABLE", name(), relative,
                                "Binary asset could not be read: " + unreadable);
                    }

                    if (copyBinaries && bytes != null) {
                        try {
                            AtomicFileWriter.writeBytes(context.writer().resolve("assets/" + relative), bytes);
                            record.addProperty("copied", true);
                            copiedBytes += bytes.length;
                        } catch (IOException | RuntimeException failed) {
                            record.addProperty("copied", false);
                            context.errors().warning("ASSET_COPY_FAILED", name(), relative,
                                    "Binary asset could not be copied: " + failed);
                        }
                    }

                    String extension = extensionOf(relative);
                    binaryByType.addProperty(extension,
                            binaryByType.has(extension) ? binaryByType.get(extension).getAsInt() + 1 : 1);
                }

                index.add(record);
                String group = groupOf(relative);
                directoryCounts.addProperty(group,
                        directoryCounts.has(group) ? directoryCounts.get(group).getAsInt() + 1 : 1);
            }
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), index.size()));
        root.addProperty("source", sourcePath(clientJar));
        root.addProperty("asset_count", index.size());
        root.addProperty("json_assets_mirrored", copied);
        root.addProperty("binary_assets_listed", binaries);
        root.addProperty("binary_assets_copied", copyBinaries ? binaries - binaryFailures : 0);
        root.addProperty("binary_assets_unreadable", binaryFailures);
        root.addProperty("binary_copy_enabled", copyBinaries);
        root.addProperty("total_bytes", totalBytes);
        root.addProperty("copied_binary_bytes", copiedBytes);
        root.addProperty("distinct_content_hashes", hashes.size());
        JsonObject sortedGroups = new JsonObject();
        new TreeMap<>(directoryCounts.asMap()).forEach(sortedGroups::add);
        root.add("groups", sortedGroups);
        root.add("binary_asset_types", sorted(binaryByType));
        root.add("assets", index);
        write(context, root);

        writeBinaryManifest(context, clientJar, hashes, binaryByType, binaries, copiedBytes);
        return index.size();
    }

    /**
     * Companion index for the binary half of the resource pack.
     *
     * <p>It answers the question the main index cannot answer cheaply: which content hashes exist,
     * how many files share one, and which file extensions the pack ships. A consumer that already has
     * the same Minecraft version can therefore prove the two packs are identical without reading
     * every byte.
     */
    private void writeBinaryManifest(ExtractorContext context, Path clientJar, TreeSet<String> hashes,
                                     JsonObject binaryByType, int binaries, long copiedBytes)
            throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.add("_meta", context.meta("asset_manifest", hashes.size()));
        manifest.addProperty("source", sourcePath(clientJar));
        manifest.addProperty("binary_asset_count", binaries);
        manifest.addProperty("distinct_content_hashes", hashes.size());
        manifest.addProperty("copied_binary_bytes", copiedBytes);
        JsonArray hashArray = new JsonArray();
        hashes.forEach(hashArray::add);
        manifest.add("content_hashes", hashArray);
        manifest.add("binary_asset_types", sorted(binaryByType));
        context.writer().write("assets/manifest.json", manifest);
    }

    private static JsonElement readJson(ZipFile zip, ZipEntry entry) {
        try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry),
                StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static byte[] readBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream stream = zip.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    /** Lower-case extension without the dot, or {@code (none)}. */
    private static String extensionOf(String relative) {
        int dot = relative.lastIndexOf('.');
        int slash = relative.lastIndexOf('/');
        return dot > slash && dot >= 0
                ? relative.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)
                : "(none)";
    }

    private static JsonObject sorted(JsonObject source) {
        JsonObject result = new JsonObject();
        new TreeMap<>(source.asMap()).forEach(result::add);
        return result;
    }

    /** Top level asset directory: {@code models/block/oak.json} -> {@code models}. */
    private static String groupOf(String relative) {
        int slash = relative.indexOf('/');
        if (slash < 0) {
            return relative;
        }
        String head = relative.substring(0, slash);
        // models/ and textures/ are the two trees worth splitting one level deeper.
        if ((head.equals("models") || head.equals("textures")) && relative.indexOf('/', slash + 1) > 0) {
            return relative.substring(0, relative.indexOf('/', slash + 1));
        }
        return head;
    }

    /**
     * Stable provenance string for the client jar: relative to the working directory when the jar
     * lives inside it, otherwise the normalised absolute path. This keeps the output independent of
     * whether the caller supplied a relative or absolute jar path.
     */
    static String sourcePath(Path clientJar) {
        Path absolute = clientJar.toAbsolutePath().normalize();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        return absolute.startsWith(workingDirectory)
                ? workingDirectory.relativize(absolute).toString()
                : absolute.toString();
    }

    /** Directory names that always contain assets, for documentation and tests. */
    public static final List<String> KNOWN_GROUPS = List.of(
            "atlases", "blockstates", "equipment", "font", "items", "lang", "models",
            "particles", "post_effect", "shaders", "sounds", "textures", "waypoint_style"
    );
}

