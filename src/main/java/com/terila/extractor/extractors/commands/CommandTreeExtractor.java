package com.terila.extractor.extractors.commands;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.RawNbtWriter;
import com.terila.extractor.util.WireFormat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Writes {@code commands/tree.json}: the whole Brigadier command tree, recursively.
 *
 * <p>Every node records whether it is a literal or an argument, which argument parser it uses
 * (as a registry identifier), whether it is executable, whether it redirects, its suggestions
 * provider and its children.
 */
public final class CommandTreeExtractor extends AbstractExtractor {

    public CommandTreeExtractor() {
        super("commands", DataType.COMMANDS, "commands/tree.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        var commands = context.commands().orElse(null);
        if (commands == null) {
            context.errors().warning("COMMANDS_UNAVAILABLE", name(), null,
                    "The command dispatcher requires a loaded server; skipped in this environment");
            return 0;
        }

        CommandDispatcher<CommandSourceStack> dispatcher = commands.getDispatcher();
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();

        // The tree is written recursively, so the entry count is every node, not just the top level.
        int total = count(root);

        JsonObject rootJson = new JsonObject();
        rootJson.addProperty("type", "root");
        rootJson.add("children", children(root));

        JsonObject document = new JsonObject();
        document.add("_meta", context.meta(name(), total));
        document.addProperty("root_command_count", root.getChildren().size());
        document.add("tree", rootJson);
        write(context, document);

        Tag nbt = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, rootJson);
        ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(nbtBytes)) {
            WireFormat.writeNbt(output, nbt);
        }
        byte[] payload = nbtBytes.toByteArray();
        if (context.config().nbtFormat().writesWire()) {
            // The wire form keeps the VarInt length prefix expected by the packet codec. The raw
            // form below writes the same NBT bytes without that frame.
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(framed)) {
                WireFormat.writeVarInt(output, payload.length);
                output.write(payload);
            }
            Path target = context.writer().resolve("commands/tree.nbt");
            Files.createDirectories(target.getParent());
            Files.write(target, framed.toByteArray());
        }
        if (context.config().nbtFormat().writesRaw()) {
            // The command tree has one logical entry, so its manifest points at tree.nbt directly
            // and records the recursive node count for a consumer that does not want to parse it.
            RawNbtWriter.writeNbt(context, "commands/raw/tree.nbt", nbt);
            JsonObject manifest = new JsonObject();
            JsonObject tree = new JsonObject();
            tree.addProperty("file", "tree.nbt");
            tree.addProperty("node_count", total);
            manifest.add("tree", tree);
            context.writer().write("commands/raw/manifest.json", manifest);
        }

        return total;
    }

    /**
     * Children keyed by name, sorted.
     *
     * <p>Brigadier exposes children in registration order. The game registers its commands from a
     * structure that is not ordered the same way on every start, so writing that order straight to
     * JSON made {@code commands/tree.json} differ between two runs of identical data. Sorting makes
     * the document reproducible without changing its meaning: a command tree is a map of names.
     */
    private static JsonObject children(CommandNode<CommandSourceStack> node) {
        List<CommandNode<CommandSourceStack>> sorted = new ArrayList<>(node.getChildren());
        sorted.sort(Comparator.comparing(CommandNode::getName));

        JsonObject children = new JsonObject();
        for (CommandNode<CommandSourceStack> child : sorted) {
            children.add(child.getName(), describe(child));
        }
        return children;
    }

    private static JsonObject describe(CommandNode<CommandSourceStack> node) {
        JsonObject json = new JsonObject();

        if (node instanceof LiteralCommandNode<CommandSourceStack>) {
            json.addProperty("type", "literal");
        } else if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            json.addProperty("type", "argument");
            var argumentType = argument.getType();
            // The argument type registry stores the serialisers (ArgumentTypeInfo), so the type has
            // to be mapped back through ArgumentTypeInfos to find its registry name.
            var info = ArgumentTypeInfos.byClass(argumentType);
            var key = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(info);
            json.addProperty("parser", key != null ? key.toString() : argumentType.getClass().getSimpleName());
            json.addProperty("parser_class", argumentType.getClass().getSimpleName());

            Collection<String> examples = argumentType.getExamples();
            if (!examples.isEmpty()) {
                List<String> sortedExamples = new ArrayList<>(examples);
                sortedExamples.sort(Comparator.naturalOrder());
                JsonArray exampleArray = new JsonArray();
                sortedExamples.forEach(exampleArray::add);
                json.add("examples", exampleArray);
            }
        } else {
            json.addProperty("type", "unknown");
        }

        json.addProperty("executable", node.getCommand() != null);
        if (node.getRedirect() != null) {
            json.addProperty("redirect", node.getRedirect().getName());
        }
        if (node.getRequirement() != null) {
            json.addProperty("has_requirement", true);
        }
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument
                && argument.getCustomSuggestions() != null) {
            json.addProperty("suggestions", "custom");
        }

        JsonObject children = children(node);
        if (!children.isEmpty()) {
            json.add("children", children);
        }
        return json;
    }

    /** Recursively counts the nodes of the tree. */
    private static int count(CommandNode<CommandSourceStack> node) {
        int total = 1;
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            total += count(child);
        }
        return total;
    }
}

