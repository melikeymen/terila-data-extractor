package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * Validates JSON documents against a practical subset of JSON Schema.
 *
 * <p>Supported keywords: {@code type}, {@code required}, {@code properties}, {@code items},
 * {@code enum}, {@code minimum}, {@code maximum} and {@code additionalProperties}. That subset is
 * enough for the schemas under {@code schema/} and keeps the extractor free of additional
 * dependencies.
 */
public final class SchemaValidator {

    private final JsonObject schema;

    public SchemaValidator(JsonObject schema) {
        this.schema = schema;
    }

    /**
     * Validates {@code document}, reporting every problem into {@code context}.
     *
     * @param subject label used in the reported issues, e.g. {@code blocks/minecraft.json}.
     */
    public ValidationResult validate(JsonElement document, String subject, ValidationContext context) {
        walk(schema, document, subject, "$", context);
        return context.toResult(subject);
    }

    /**
     * Walks one node of the schema against the matching part of the document.
     *
     * @param subject label of the document being validated, used in the reported issues.
     * @param pointer JSON pointer of the value under inspection, e.g. {@code $.data[3].id}.
     */
    private void walk(JsonObject node, JsonElement value, String subject, String pointer,
                      ValidationContext context) {
        if (node.has("type")) {
            String expected = node.get("type").getAsString();
            if (!matchesType(expected, value)) {
                context.report(ExtractionIssue.error("SCHEMA_TYPE", subject, pointer, "type",
                        "Expected " + expected + " at " + pointer));
                return;
            }
        }

        if (node.has("enum") && value.isJsonPrimitive()) {
            JsonArray allowed = node.getAsJsonArray("enum");
            boolean found = false;
            for (JsonElement candidate : allowed) {
                if (candidate.equals(value)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                context.report(ExtractionIssue.error("SCHEMA_ENUM", subject, pointer, null,
                        "Value " + value + " is not one of " + allowed + " at " + pointer));
            }
        }

        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double number = value.getAsDouble();
            if (node.has("minimum") && number < node.get("minimum").getAsDouble()) {
                context.outOfRange(pointer, null, value, ">= " + node.get("minimum").getAsDouble());
            }
            if (node.has("maximum") && number > node.get("maximum").getAsDouble()) {
                context.outOfRange(pointer, null, value, "<= " + node.get("maximum").getAsDouble());
            }
        }

        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (node.has("required")) {
                for (JsonElement required : node.getAsJsonArray("required")) {
                    String field = required.getAsString();
                    if (!object.has(field)) {
                        context.missingField(pointer, field);
                    }
                }
            }
            JsonObject properties = node.has("properties") ? node.getAsJsonObject("properties") : null;
            boolean allowAdditional = !node.has("additionalProperties")
                    || node.get("additionalProperties").getAsBoolean();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (properties != null && properties.has(entry.getKey())) {
                    walk(properties.getAsJsonObject(entry.getKey()), entry.getValue(), subject,
                            pointer + "." + entry.getKey(), context);
                } else if (!allowAdditional) {
                    context.unknownProperty(pointer, entry.getKey());
                }
            }
        }

        if (value.isJsonArray() && node.has("items")) {
            JsonObject items = node.getAsJsonObject("items");
            JsonArray array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                walk(items, array.get(i), subject, pointer + "[" + i + "]", context);
            }
        }
    }

    private static boolean matchesType(String expected, JsonElement value) {
        return switch (expected) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && isIntegral(value.getAsJsonPrimitive());
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "null" -> value.isJsonNull();
            default -> true;
        };
    }

    private static boolean isIntegral(JsonPrimitive primitive) {
        double value = primitive.getAsDouble();
        return value == Math.floor(value) && !Double.isInfinite(value);
    }
}
