package com.terila.extractor.core;

// Written by AI
import java.util.List;

/**
 * Outcome of validating one payload against a schema.
 *
 * @param subject  what was validated, e.g. {@code blocks/minecraft.json}.
 * @param issues   problems found; empty means the payload is valid.
 */
public record ValidationResult(String subject, List<ExtractionIssue> issues) {

    /** Creates a result with no issues. */
    public static ValidationResult valid(String subject) {
        return new ValidationResult(subject, List.of());
    }

    /** Whether validation produced no issues of any severity. */
    public boolean isValid() {
        return issues.isEmpty();
    }

    /** Issues with error severity. */
    public List<ExtractionIssue> errors() {
        return issues.stream().filter(i -> i.severity() == ExtractionIssue.Severity.ERROR).toList();
    }
}
