package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonObject;

/**
 * A single problem encountered while extracting or validating data.
 *
 * @param severity  {@link Severity#ERROR} aborts nothing by itself (error isolation is per
 *                  extractor), {@link Severity#WARNING} is informational.
 * @param type      machine readable category, e.g. {@code MISSING_FIELD}.
 * @param extractor name of the extractor that reported the issue.
 * @param entry     the offending entry, e.g. {@code minecraft:stone}; may be {@code null}.
 * @param field     the offending field; may be {@code null}.
 * @param message   human readable description.
 */
public record ExtractionIssue(
        Severity severity,
        String type,
        String extractor,
        String entry,
        String field,
        String message
) {

    /** Severity of an issue; errors do not abort the run by themselves. */
    public enum Severity { ERROR, WARNING }

    /** Creates an error with an optional entry and field location. */
    public static ExtractionIssue error(String type, String extractor, String entry, String field, String message) {
        return new ExtractionIssue(Severity.ERROR, type, extractor, entry, field, message);
    }

    /** Creates a warning without a field location. */
    public static ExtractionIssue warning(String type, String extractor, String entry, String message) {
        return new ExtractionIssue(Severity.WARNING, type, extractor, entry, null, message);
    }

    /**
     * Warning variant that also names the offending field.
     *
     * <p>Used by validation, where the location ({@code entry}) and the field that broke the contract
     * are two different things: {@code $.data[3]} and {@code protocol_id}.
     */
    public static ExtractionIssue warning(String type, String extractor, String entry, String field,
                                          String message) {
        return new ExtractionIssue(Severity.WARNING, type, extractor, entry, field, message);
    }

    /** Renders the issue for {@code _errors.json}; null locations are omitted. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("severity", severity.name());
        json.addProperty("type", type);
        if (extractor != null) {
            json.addProperty("extractor", extractor);
        }
        if (entry != null) {
            json.addProperty("entry", entry);
        }
        if (field != null) {
            json.addProperty("field", field);
        }
        json.addProperty("message", message);
        return json;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(severity).append(" [").append(type).append("]");
        if (extractor != null) {
            builder.append(" ").append(extractor);
        }
        if (entry != null) {
            builder.append(" ").append(entry);
        }
        if (field != null) {
            builder.append(".").append(field);
        }
        return builder.append(": ").append(message).toString();
    }
}
