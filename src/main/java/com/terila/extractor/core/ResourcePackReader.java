package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Reads raw pack files through a {@link ResourceManager} instead of a directory.
 *
 * <p>This is what makes the head-less run complete: the vanilla data pack lives inside the server
 * jar, so walking it with {@link java.nio.file.Files} is impossible. Going through the resource
 * manager reaches every loaded pack — vanilla and any datapack stacked on top of it — with the same
 * override rules the game uses.
 */
public final class ResourcePackReader implements PackReader {

    private final ResourceManager resources;

    private ResourcePackReader(ResourceManager resources) {
        this.resources = resources;
    }

    /** Wraps a loaded resource manager as a pack reader. */
    public static ResourcePackReader of(ResourceManager resources) {
        return new ResourcePackReader(resources);
    }

    /** Always available: a resource manager exists even when it has no packs. */
    @Override
    public boolean isAvailable() {
        return true;
    }

    /** Lists the active pack ids, useful in warnings and diagnostics. */
    @Override
    public String describe() {
        return resources.listPacks().map(PackResources::packId).collect(Collectors.joining(", "));
    }

    @Override
    public Map<String, JsonElement> loadAll(String directory) {
        Map<String, JsonElement> loaded = new TreeMap<>();
        for (Map.Entry<String, Resource> entry : resources(directory, ".json").entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                loaded.put(entry.getKey(), JsonParser.parseReader(reader));
            } catch (IOException failure) {
                throw new UncheckedIOException("Failed to read " + entry.getKey(), failure);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Failed to parse " + entry.getKey(), failure);
            }
        }
        return loaded;
    }

    @Override
    public Map<String, String> loadTextAll(String directory, String extension) {
        Map<String, String> loaded = new TreeMap<>();
        for (Map.Entry<String, Resource> entry : resources(directory, extension).entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, read);
                }
                loaded.put(entry.getKey(), content.toString());
            } catch (IOException failure) {
                throw new UncheckedIOException("Failed to read " + entry.getKey(), failure);
            }
        }
        return loaded;
    }

    /**
     * Every resource below {@code directory} with the given extension, keyed canonically.
     *
     * <p>The resource manager already applies pack precedence, so an override in a datapack replaces
     * the vanilla file rather than appearing twice.
     */
    private Map<String, Resource> resources(String directory, String extension) {
        Map<String, Resource> found = new TreeMap<>();
        for (Map.Entry<Identifier, Resource> entry : resources
                .listResources(directory, id -> id.getPath().endsWith(extension)).entrySet()) {
            String key = keyOf(entry.getKey(), directory, extension);
            if (key != null) {
                found.put(key, entry.getValue());
            }
        }
        return found;
    }

    /**
     * Turns {@code minecraft:loot_table/blocks/oak.json} (as returned for the {@code loot_table}
     * directory) into the canonical {@code minecraft:blocks/oak}, matching the filesystem reader.
     * The same rule applies to {@code minecraft:function/tick.mcfunction} → {@code minecraft:tick}.
     */
    private static String keyOf(Identifier id, String directory, String extension) {
        String path = id.getPath();
        if (path.startsWith(directory + "/")) {
            path = path.substring(directory.length() + 1);
        }
        if (path.endsWith(extension)) {
            path = path.substring(0, path.length() - extension.length());
        }
        return path.isEmpty() ? null : id.getNamespace() + ":" + path;
    }
}

