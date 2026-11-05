package com.terila.extractor.core;

// Written by AI
import java.util.ArrayList;
import java.util.List;

/**
 * Collects validation problems while an extractor walks its data.
 *
 * <p>Mirrors the layered validation described in the design: raw data, transformation, JSON schema
 * and cross-reference checks all report through this type so the run has a single error report.
 *
 * <p>Every method takes a <strong>JSON pointer</strong> as its first argument — {@code $.data[3]} —
 * and the offending <em>field name</em> as its second, when there is one. Keeping those two
 * consistent is what makes an issue actionable: the pointer says where in the document to look and
 * the field says which member broke the contract. Both end up in {@link ExtractionIssue}, under
 * {@code entry} and {@code field} respectively.
 */
public final class ValidationContext {

    private final String extractorName;
    private final ErrorCollector sink;
    private final List<ExtractionIssue> local = new ArrayList<>();
    private final boolean strict;

    /** Creates a non-strict validation context that forwards issues to {@code sink}. */
    public ValidationContext(String extractorName, ErrorCollector sink) {
        this(extractorName, sink, false);
    }

    /**
     * Creates a validation context.
     *
     * @param extractorName producer recorded on every issue.
     * @param sink          run-level collector that also receives the issues.
     * @param strict        whether unknown properties should be treated as failures by callers.
     */
    public ValidationContext(String extractorName, ErrorCollector sink, boolean strict) {
        this.extractorName = extractorName;
        this.sink = sink;
        this.strict = strict;
    }

    /** A required member is absent. */
    public void missingField(String pointer, String field) {
        report(ExtractionIssue.error("MISSING_FIELD", extractorName, pointer, field,
                "Required field '" + field + "' is missing"));
    }

    /** A member holds a value of the wrong type. */
    public void invalidType(String pointer, String field, String expected, Object actual) {
        report(ExtractionIssue.error("INVALID_TYPE", extractorName, pointer, field,
                "Expected " + expected + " but found "
                        + (actual == null ? "null" : actual.getClass().getSimpleName())));
    }

    /** A number is outside its allowed range. */
    public void outOfRange(String pointer, String field, Object value, String range) {
        report(ExtractionIssue.error("OUT_OF_RANGE", extractorName, pointer, field,
                "Value " + value + " is outside " + range));
    }

    /** A reference does not resolve. */
    public void danglingReference(String pointer, String field, String referenced) {
        report(ExtractionIssue.error("DANGLING_REFERENCE", extractorName, pointer, field,
                "Reference '" + referenced + "' does not resolve"));
    }

    /** A member is present that the contract does not allow. */
    public void unknownProperty(String pointer, String property) {
        report(ExtractionIssue.warning("UNKNOWN_PROPERTY", extractorName, pointer, property,
                "Unknown property: " + property));
    }

    /** Free-form warning with the location as the entry. */
    public void warn(String type, String entry, String message) {
        report(ExtractionIssue.warning(type, extractorName, entry, message));
    }

    /** Forwards an already built issue. */
    public void report(ExtractionIssue issue) {
        local.add(issue);
        sink.add(issue);
    }

    /** Issues raised through this context only. */
    public List<ExtractionIssue> issues() {
        return List.copyOf(local);
    }

    /** Whether unknown properties should be treated as failures by the caller. */
    public boolean isStrict() {
        return strict;
    }

    /** Whether this context has recorded at least one error. */
    public boolean hasErrors() {
        return local.stream().anyMatch(i -> i.severity() == ExtractionIssue.Severity.ERROR);
    }

    /** Packages the local issues into a result with the supplied subject label. */
    public ValidationResult toResult(String subject) {
        return new ValidationResult(subject, issues());
    }
}
