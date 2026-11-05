package com.terila.extractor.extractors.recipes;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the recipe catalogue.
 *
 * <p>Recipes are the data set that moved into a registry in 26.3, which is exactly when the codec
 * lookup broke: referencing another registry from a recipe payload failed to resolve and the entry
 * was written without its document. These tests check the shape and the aggregate counts, both of
 * which the schema table also covers from the other direction.
 */
class RecipeExtractorTest {

    /** One entry of the recipe document. */
    private record Recipe(String id, String type, JsonObject recipe) {
    }

    @Test
    void matchesTheLoadedRecipeManager() {
        int expected = TestSupport.extraction().groundTruth().recipes();
        JsonObject document = TestSupport.document("recipes/minecraft.json");

        assertEquals(expected, document.getAsJsonObject("_meta").get("entry_count").getAsInt());
        assertEquals(expected, document.getAsJsonArray("data").size());
        assertTrue(expected > 1_000, "expected a full recipe set, got " + expected);
    }

    /** The grouped files have to partition the same set, with nothing counted twice. */
    @Test
    void groupedFilesAddUpToTheSameTotal() throws Exception {
        Path directory = TestSupport.extraction().output().resolve("recipes");
        int grouped = 0;
        List<String> ids = new ArrayList<>();

        try (Stream<Path> stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("minecraft.json"))
                    .sorted()
                    .toList()) {
                JsonObject document = TestSupport.object(file);
                grouped += document.getAsJsonObject("_meta").get("entry_count").getAsInt();
                document.getAsJsonArray("data").forEach(entry ->
                        ids.add(entry.getAsJsonObject().get("id").getAsString()));
            }
        }

        assertTrue(grouped > 0, "expected at least one grouped recipe file");
        assertEquals(TestSupport.extraction().groundTruth().recipes(), grouped);
        assertEquals(grouped, ids.stream().distinct().count(), "a recipe appears in two groups");
    }

    /** Every entry has to carry its payload; a reference is what the 26.3 regression looked like. */
    @Test
    void everyRecipeCarriesItsPayloadAndType() {
        for (Recipe recipe : recipes()) {
            assertTrue(recipe.recipe().isJsonObject(),
                    recipe.id() + " has no payload: " + recipe.recipe());
            assertFalse(recipe.recipe().isEmpty(), recipe.id() + " has an empty payload");
            assertTrue(recipe.type().contains(":"), recipe.id() + " has a malformed type " + recipe.type());
        }
    }

    /**
     * The entry's type is the recipe <em>type</em> registry entry; the payload names the serializer.
     *
     * <p>They are different registries (a {@code minecraft:crafting} recipe is serialised by
     * {@code minecraft:crafting_shaped}), so both have to resolve independently.
     */
    @Test
    void theDeclaredTypeAndThePayloadsSerializerBothResolve() {
        Set<String> types = registryIds("misc/recipe_types.json");
        Set<String> serializers = registryIds("misc/recipe_serializers.json");

        assertFalse(types.isEmpty());
        assertFalse(serializers.isEmpty());

        for (Recipe recipe : recipes()) {
            assertTrue(types.contains(recipe.type()),
                    recipe.id() + " uses unknown recipe type " + recipe.type());

            JsonElement declared = recipe.recipe().get("type");
            assertTrue(declared != null && declared.isJsonPrimitive(),
                    recipe.id() + " payload has no serializer name");
            assertTrue(serializers.contains(declared.getAsString()),
                    recipe.id() + " uses unknown serializer " + declared.getAsString());
        }
    }

    @Test
    void identifiersAreUniqueAndSorted() {
        List<String> ids = recipes().stream().map(Recipe::id).toList();

        assertEquals(TestSupport.sorted(ids), ids, "recipes are not in identifier order");
        assertEquals(ids.size(), ids.stream().distinct().count(), "duplicate recipe ids");
    }

    /** Identifiers of a {@code {_meta, data: [{id, ..}]}} registry document. */
    private static Set<String> registryIds(String relativePath) {
        return TestSupport.entries(TestSupport.document(relativePath)).stream()
                .map(entry -> entry.get("id").getAsString())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static List<Recipe> recipes() {
        return TestSupport.entries(TestSupport.document("recipes/minecraft.json")).stream()
                .map(entry -> new Recipe(
                        entry.get("id").getAsString(),
                        entry.get("type").getAsString(),
                        entry.getAsJsonObject("recipe")))
                .toList();
    }
}
