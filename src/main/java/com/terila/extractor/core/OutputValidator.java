package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Validates the written output against {@link DocumentSchemas} once extraction has finished.
 *
 * <p>This is the layer the design calls "JSON schema validation": every document is read back from
 * disk and checked against the schema for its path, so a defect is caught at the end of the run
 * instead of by whoever consumes the data later. Reading the files rather than the in-memory
 * documents is deliberate — it verifies what actually landed on disk, including the writing step.
 *
 * <p>Problems are recorded through the ordinary {@link ErrorCollector}, so they end up in
 * {@code _errors.json} next to the extraction issues, and a machine readable summary is written to
 * {@code _validation.json}.
 *
 * <p>Cheap: the pass is a single sequential walk over files that are already in the OS page cache,
 * and it is skipped entirely when {@link ExtractorConfig#validate()} is off.
 */
public final class OutputValidator {

    /** Label used for issues that are about the output as a whole rather than one document. */
    private static final String SCOPE = "output_validation";

    private OutputValidator() {
    }

    /**
     * Outcome of a validation pass.
     *
     * @param documents how many JSON documents were found.
     * @param validated how many of them had a schema and were checked.
     * @param unchecked documents with no schema and no metadata block (e.g. a mirrored asset).
     * @param errors    validation errors raised by this pass.
     * @param warnings  validation warnings raised by this pass.
     */
    public record Summary(int documents, int validated, int unchecked, int errors, int warnings) {

        public boolean isValid() {
            return errors == 0;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("documents", documents);
            json.addProperty("validated", validated);
            json.addProperty("unchecked", unchecked);
            json.addProperty("errors", errors);
            json.addProperty("warnings", warnings);
            json.addProperty("valid", isValid());
            return json;
        }
    }

    /**
     * Walks {@code root} and validates every document it finds.
     *
     * @param root   output directory to inspect.
     * @param config run configuration; only {@code validate} is read, by the caller.
     * @param errors sink for the problems found.
     * @return a summary of the pass.
     */
    public static Summary validate(Path root, ExtractorConfig config, ErrorCollector errors) {
        List<Path> documents = jsonDocuments(root);
        int validated = 0;
        int unchecked = 0;
        int errorsBefore = errors.errorCount();
        int warningsBefore = errors.warningCount();

        for (Path file : documents) {
            String relative = relative(root, file);
            JsonElement document;
            try {
                document = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException unreadable) {
                errors.error("OUTPUT_UNREADABLE", SCOPE, relative, null,
                        "Document could not be read back: " + unreadable);
                continue;
            } catch (RuntimeException malformed) {
                errors.error("OUTPUT_INVALID_JSON", SCOPE, relative, null,
                        "Document is not valid JSON: " + malformed.getMessage());
                continue;
            }

            Optional<JsonObject> schema = DocumentSchemas.forPath(relative);
            if (schema.isEmpty() && hasMetadata(document)) {
                // No path specific schema, but the document claims extractor metadata: the generic
                // metadata contract still applies.
                schema = Optional.of(DocumentSchemas.metaCarrier());
            }
            if (schema.isEmpty()) {
                unchecked++;
                continue;
            }

            ValidationContext context = new ValidationContext(relative, errors);
            new SchemaValidator(schema.get()).validate(document, relative, context);
            validated++;
            checkDataShape(document, relative, errors);
        }

        return new Summary(documents.size(), validated, unchecked,
                errors.errorCount() - errorsBefore, errors.warningCount() - warningsBefore);
    }

    /**
     * The one structural rule that is not expressible as a schema: in every document that has both,
     * {@code data} is the array of entries, never a map.
     *
     * <p>Four documents legitimately carry a keyed {@code data} member (the language files, the biome
     * parameter lists and the per-item component defaults); they are listed in
     * {@link DocumentSchemas#dataMayBeObject(String)}. For everything else a map here means an
     * extractor changed the shape of its output, which is what a consumer must never have to absorb
     * silently.
     */
    private static void checkDataShape(JsonElement document, String relative, ErrorCollector errors) {
        if (!document.isJsonObject() || DocumentSchemas.dataMayBeObject(relative)) {
            return;
        }
        JsonElement data = document.getAsJsonObject().get("data");
        if (data != null && !data.isJsonArray()) {
            errors.error("OUTPUT_DATA_NOT_ARRAY", SCOPE, relative, "data",
                    "data must be an array of entries but is " + (data.isJsonObject() ? "an object" : data));
        }
    }

    private static boolean hasMetadata(JsonElement document) {
        return document.isJsonObject() && document.getAsJsonObject().has("_meta");
    }

    private static List<Path> jsonDocuments(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> documents = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                if (DocumentSchemas.isMirroredAsset(relative(root, file))) {
                    continue;
                }
                documents.add(file);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to list " + root, failure);
        }
        return documents;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
