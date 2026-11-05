package com.terila.extractor.extractors.recipes;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.RawNbtWriter;
import com.terila.extractor.util.WireFormat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Extracts every loaded recipe into {@code recipes/}, grouped into the files the design specifies:
 * {@code shaped}, {@code shapeless}, {@code cooking}, {@code smithing}, {@code stonecutting},
 * {@code special} and {@code misc}.
 *
 * <p>Recipes are serialised through {@link Recipe#DIRECT_CODEC}, which dispatches on the recipe
 * serializer, so every recipe type keeps its full, exact representation — including the vanilla
 * serialisers that the hand written shaped/shapeless extractors of the design would have had to
 * duplicate.
 *
 * <p>26.3 wrapped the recipe registry in holders, so the top level {@code Recipe.CODEC} now encodes a
 * {@code Holder<Recipe<?>>}. The direct codec is what dumps a single recipe.
 */
public final class RecipeExtractor extends AbstractExtractor {

    public RecipeExtractor() {
        super("recipes", DataType.RECIPES, "recipes/minecraft.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        var recipeManager = context.recipeManager().orElse(null);
        if (recipeManager == null) {
            context.errors().warning("RECIPE_MANAGER_UNAVAILABLE", name(), null,
                    "Recipes require a loaded server; skipped in this environment");
            return 0;
        }

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, context.lookup());
        RegistryOps<Tag> nbtOps = RegistryOps.create(NbtOps.INSTANCE, context.lookup());

        Map<String, JsonArray> byGroup = new TreeMap<>();
        JsonArray all = new JsonArray();
        List<WireRecipe> wire = new ArrayList<>();
        int count = 0;

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            String id = holder.id().identifier().toString();
            try {
                Recipe<?> recipe = holder.value();
                // Canonicalised: a recipe result is an ItemStack, whose component map is keyed on
                // identity and would otherwise order its members differently per JVM.
                JsonElement encoded = com.terila.extractor.util.JsonUtil.canonical(
                        Recipe.DIRECT_CODEC.encodeStart(ops, recipe).result().orElse(null));
                if (encoded == null) {
                    context.errors().error("RECIPE_ENCODE_FAILED", name(), id, null,
                            "Recipe codec returned no result");
                    continue;
                }

                String typeId = recipeTypeId(recipe);
                JsonObject entry = new JsonObject();
                entry.addProperty("id", id);
                entry.addProperty("type", typeId);
                entry.add("recipe", encoded);

                all.add(entry);
                byGroup.computeIfAbsent(groupOf(typeId), ignored -> new JsonArray()).add(entry);

                Tag nbt = Recipe.DIRECT_CODEC.encodeStart(nbtOps, recipe).result().orElse(null);
                if (nbt != null) {
                    wire.add(new WireRecipe(id, nbt));
                } else {
                    context.errors().error("RECIPE_WIRE_ENCODE_FAILED", name(), id, null,
                            "Recipe codec returned no NBT payload");
                }
                count++;
            } catch (RuntimeException failure) {
                context.errors().error("RECIPE_EXTRACTION_FAILED", name(), id, null, String.valueOf(failure));
            }
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), count));
        root.add("data", all);
        write(context, root);

        for (Map.Entry<String, JsonArray> group : new LinkedHashMap<>(byGroup).entrySet()) {
            JsonObject groupRoot = new JsonObject();
            groupRoot.add("_meta", context.meta(group.getKey(), group.getValue().size()));
            groupRoot.add("data", group.getValue());
            context.writer().write("recipes/" + group.getKey() + ".json", groupRoot);
        }

        // Both wire and raw consume the same sorted list; the only difference is framing. Sorting
        // here is what keeps two runs byte-identical even though the recipe manager is unordered.
        wire.sort(java.util.Comparator.comparing(WireRecipe::id));
        boolean wireOutput = context.config().nbtFormat().writesWire();
        boolean rawOutput = context.config().nbtFormat().writesRaw();
        if (wireOutput) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                WireFormat.writeVarInt(output, wire.size());
                for (WireRecipe recipe : wire) {
                    WireFormat.writeString(output, recipe.id());
                    WireFormat.writeNbt(output, recipe.nbt());
                }
            }
            Path target = context.writer().resolve("recipes/minecraft.nbt");
            Files.createDirectories(target.getParent());
            Files.write(target, bytes.toByteArray());
        }
        if (rawOutput) {
            JsonObject manifest = new JsonObject();
            Set<String> usedNames = new HashSet<>();
            for (WireRecipe recipe : wire) {
                // The manifest file is relative to recipes/raw/, the same directory that holds the
                // per-recipe NBT files. Collision suffixes only appear for custom pack ids.
                String file = RawNbtWriter.uniqueFileName(recipe.id(), usedNames);
                RawNbtWriter.writeNbt(context, "recipes/raw/" + file, recipe.nbt());
                JsonObject item = new JsonObject();
                item.addProperty("file", file);
                manifest.add(recipe.id(), item);
            }
            context.writer().write("recipes/raw/manifest.json", manifest);
        }

        return count;
    }

    /** One recipe id and its unnamed NBT codec payload. */
    private record WireRecipe(String id, Tag nbt) {
    }

    /** Identifier of the recipe type, e.g. {@code minecraft:crafting_shaped}. */
    static String recipeTypeId(Recipe<?> recipe) {
        var type = recipe.getType();
        var key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return key != null ? key.toString() : "minecraft:unknown";
    }

    /** Maps a recipe type onto the output file the design specifies. */
    static String groupOf(String typeId) {
        String path = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        return switch (path) {
            case "crafting_shaped" -> "shaped";
            case "crafting_shapeless" -> "shapeless";
            case "smelting", "blasting", "smoking", "campfire_cooking" -> "cooking";
            case "smithing_transform", "smithing_trim" -> "smithing";
            case "stonecutting" -> "stonecutting";
            default -> path.startsWith("crafting_special") || path.startsWith("crafting_")
                    ? "special" : "misc";
        };
    }
}
