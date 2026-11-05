package com.terila.extractor.extractors.datapack;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.PackReader;
import com.terila.extractor.core.RawNbtWriter;
import com.terila.extractor.util.WireFormat;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Base class for data that only exists as datapack files.
 *
 * <p>Advancements, loot tables, predicates and item modifiers are not registries: the game loads
 * them straight from JSON. This extractor reads them verbatim from every loaded pack — the vanilla
 * data pack inside the server jar included — keyed by their canonical identifier, so nothing is
 * lost to a partial model.
 *
 * <p>Only {@code data/minecraft/item_modifier} ships empty in vanilla: Mojang defines the vocabulary
 * but no content. An empty array alone cannot be told apart from a failed extraction, so the
 * document states whether vanilla provides content and carries the type vocabulary a data pack may
 * use. Subclasses fill that in through {@link #decorate(ExtractorContext, JsonObject)}.
 *
 * <p>{@code data/minecraft/function} is a different case: it holds command sources, not JSON, and is
 * read by {@link com.terila.extractor.extractors.functions.FunctionExtractor}.
 */
public abstract class DatapackJsonExtractor extends AbstractExtractor {

    private final String directory;
    private final String nbtOutputPath;

    /**
     * @param name       stable identifier reported in logs and {@code _meta}.
     * @param type       category that controls whether the extractor runs.
     * @param outputPath document written relative to the output root.
     * @param directory  directory inside {@code data/<namespace>/} that holds the entries.
     */
    protected DatapackJsonExtractor(String name, DataType type, String outputPath, String directory) {
        this(name, type, outputPath, directory, null);
    }

    /**
     * @param nbtOutputPath optional wire-format NBT path; when set, entries are also written as
     *                      {@code count VarInt + id String + unnamed NBT}.
     */
    protected DatapackJsonExtractor(String name, DataType type, String outputPath, String directory,
                                    String nbtOutputPath) {
        super(name, type, outputPath);
        this.directory = directory;
        this.nbtOutputPath = nbtOutputPath;
    }

    /** The data pack directory this extractor copies, e.g. {@code advancement}. */
    public String directory() {
        return directory;
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        PackReader reader = context.pack().orElse(null);
        if (reader == null) {
            context.errors().warning("DATAPACK_UNAVAILABLE", name(), directory,
                    "No data pack available; " + directory + " entries were skipped");
            return 0;
        }

        // PackReader returns a sorted map, so the JSON document, the wire stream and the raw
        // manifest all iterate the entries in the same deterministic order.
        Map<String, JsonElement> entries = reader.loadAll(directory);

        JsonArray data = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", entry.getKey());
            json.add("value", entry.getValue());
            data.add(json);
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), entries.size()));
        root.addProperty("directory", "data/<namespace>/" + directory);
        root.addProperty("vanilla_provides", !entries.isEmpty());
        if (entries.isEmpty()) {
            root.addProperty("note", "Vanilla Minecraft ships no entries for this directory");
        }
        decorate(context, root);
        root.add("data", data);
        write(context, root);
        if (nbtOutputPath != null && context.config().nbtFormat().writesWire()) {
            writeWire(context, entries);
        }
        if (nbtOutputPath != null && context.config().nbtFormat().writesRaw()) {
            writeRaw(context, entries);
        }
        return entries.size();
    }

    /** Writes the same entries as a counted sequence of ids and unnamed NBT tags. */
    private void writeWire(ExtractorContext context, Map<String, JsonElement> entries)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            WireFormat.writeVarInt(output, entries.size());
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                WireFormat.writeString(output, entry.getKey());
                Tag tag = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, entry.getValue());
                WireFormat.writeNbt(output, tag);
            }
        }
        Path target = context.writer().resolve(nbtOutputPath);
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.write(target, bytes.toByteArray());
    }

    /** Writes each entry as an unframed NBT tag plus its id-to-file manifest. */
    private void writeRaw(ExtractorContext context, Map<String, JsonElement> entries)
            throws IOException {
        String rawRoot = nbtOutputPath.substring(0, nbtOutputPath.indexOf('/')) + "/raw/";
        JsonObject manifest = new JsonObject();
        Set<String> usedNames = new HashSet<>();

        // Copy into a TreeMap even though loadAll is documented as sorted: callers may supply a
        // custom reader, and the manifest itself must be deterministic either way.
        for (Map.Entry<String, JsonElement> entry : new TreeMap<>(entries).entrySet()) {
            Tag tag = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, entry.getValue());
            String file = RawNbtWriter.uniqueFileName(entry.getKey(), usedNames);
            RawNbtWriter.writeNbt(context, rawRoot + file, tag);

            JsonObject item = new JsonObject();
            item.addProperty("file", file);
            manifest.add(entry.getKey(), item);
        }

        context.writer().write(rawRoot + "manifest.json", manifest);
    }

    /**
     * Hook for subclasses that want to add extra context to the document.
     *
     * <p>Used to attach the type vocabulary a data pack may use for a directory that vanilla itself
     * leaves empty.
     *
     * @param context the run's context.
     * @param root    the document about to be written; mutate it in place.
     */
    protected void decorate(ExtractorContext context, JsonObject root) {
        // Nothing by default.
    }
}

