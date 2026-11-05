package com.terila.extractor.extractors.tags;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terila.extractor.core.ExtractorVersion;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.tags.TagNetworkSerialization;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The registries a vanilla client expects in an Update Tags packet, per protocol version.
 *
 * <p>A client only accepts a tag payload whose registry ids line up with its own registries. Sending
 * a registry vanilla does not send — or omitting one it does — makes the client fail the whole
 * payload, so the set is data, not code: it lives in
 * {@code config/versions/<version>/tag_registries.json} and the same file is used for every future
 * version without a recompile.
 *
 * <pre>
 * { "protocol": 777, "client_tag_registries": ["minecraft:banner_pattern", "..."] }
 * </pre>
 *
 * <p>The order in the file is the order the registry groups are written to
 * {@code tags/client_tags.bin}. The configuration is a capture of a vanilla client-bound
 * {@code update_tags} packet, so it also pins the order the game used.
 *
 * <p>{@link #derive} is a diagnostic, not a second source of truth: it asks the loaded game for the
 * registries its own {@code TagNetworkSerialization.serializeTagsToNetwork} would send, so a drift
 * between the configuration and the game is reported instead of silently shipping a stale list.
 */
public final class ClientTagRegistries {

    /** Output artifact built from this configuration. */
    public static final String OUTPUT_FILE = "tags/client_tags.bin";

    /** Directory the versioned configuration lives in, relative to the project root. */
    private static final String CONFIG_ROOT = "config/versions";

    /** Field holding the ordered registry list. */
    private static final String LIST_FIELD = "client_tag_registries";

    private ClientTagRegistries() {
    }

    /**
     * One version's configuration.
     *
     * @param protocol   network protocol the list was captured from.
     * @param registries registry ids in packet order.
     */
    public record Config(int protocol, List<String> registries) {

        /** Defensive copy so a caller cannot mutate the parsed list. */
        public Config {
            registries = List.copyOf(registries);
        }
    }

    /** The configuration path for the Minecraft version this run targets. */
    public static Path configPath() {
        return Path.of(CONFIG_ROOT, ExtractorVersion.MINECRAFT_VERSION, "tag_registries.json");
    }

    /**
     * Loads the configuration for the running version.
     *
     * <p>The checked-in file is preferred so a local edit is picked up immediately; a packaged run
     * falls back to the copy on the classpath, which the build places at the same relative path.
     *
     * @return the configuration, or empty when neither location has one.
     * @throws IOException when a configuration exists but cannot be read or parsed.
     */
    public static Optional<Config> load() throws IOException {
        Path file = configPath();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return Optional.of(parse(reader, file.toString()));
            }
        }

        String resource = "/" + CONFIG_ROOT + "/" + ExtractorVersion.MINECRAFT_VERSION
                + "/tag_registries.json";
        try (InputStream stream = ClientTagRegistries.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return Optional.empty();
            }
            try (Reader reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return Optional.of(parse(reader, "classpath:" + resource));
            }
        }
    }

    /**
     * Parses a configuration document.
     *
     * @param reader source of the JSON document.
     * @param source name reported in a parse failure.
     * @return the parsed configuration.
     * @throws IOException when the document is not a valid configuration.
     */
    public static Config parse(Reader reader, String source) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException("invalid tag registry configuration: " + source, invalid);
        }

        JsonElement protocol = root.get("protocol");
        if (protocol == null || !protocol.isJsonPrimitive()) {
            throw new IOException("tag registry configuration has no protocol: " + source);
        }
        JsonArray list = root.getAsJsonArray(LIST_FIELD);
        if (list == null) {
            throw new IOException("tag registry configuration has no " + LIST_FIELD + ": " + source);
        }

        List<String> registries = new ArrayList<>(list.size());
        TreeSet<String> seen = new TreeSet<>();
        for (JsonElement element : list) {
            String registry = element.getAsString();
            if (registry.isBlank()) {
                throw new IOException("blank registry id in " + source);
            }
            if (!seen.add(registry)) {
                throw new IOException("duplicate registry id in " + source + ": " + registry);
            }
            registries.add(registry);
        }
        return new Config(protocol.getAsInt(), registries);
    }

    /**
     * Asks the loaded game which registries its own Update Tags packet would carry.
     *
     * <p>This is vanilla's path exactly: {@code TagNetworkSerialization.serializeTagsToNetwork}
     * starts from {@code RegistrySynchronization.networkSafeRegistries} and drops registries whose
     * tag map is empty. A registry the game declares no tags for therefore cannot appear here, which
     * is why this is only a diagnostic against the captured configuration.
     *
     * @param layers the loaded server registry layers.
     * @return the derived registry ids, sorted.
     */
    public static List<String> derive(LayeredRegistryAccess<RegistryLayer> layers) {
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> payloads =
                TagNetworkSerialization.serializeTagsToNetwork(layers);
        return payloads.keySet().stream()
                .map(key -> key.identifier().toString())
                .sorted()
                .toList();
    }

    /**
     * Describes how a configured list and a derived list differ.
     *
     * @param configured registries from the configuration, in its order.
     * @param derived    registries the game would send, sorted.
     * @return a human readable diff, or {@code null} when the two sets are equal.
     */
    public static String difference(List<String> configured, List<String> derived) {
        TreeSet<String> onlyConfigured = new TreeSet<>(configured);
        onlyConfigured.removeAll(derived);
        TreeSet<String> onlyGame = new TreeSet<>(derived);
        onlyGame.removeAll(configured);

        if (onlyConfigured.isEmpty() && onlyGame.isEmpty()) {
            return null;
        }
        return "configured-only=" + onlyConfigured + ", game-only=" + onlyGame;
    }
}
