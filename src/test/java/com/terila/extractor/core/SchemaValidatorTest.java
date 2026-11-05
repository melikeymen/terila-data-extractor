package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the JSON Schema subset the output validation is built on.
 *
 * <p>The validator is the gate every document passes through, so it has to be right in both
 * directions: it must accept a conforming document and it must reject each way a document can break
 * the contract. A validator that silently accepts everything would make the validation pass
 * worthless, which is why the negative cases are the bulk of this class.
 */
class SchemaValidatorTest {

    private static final JsonObject SCHEMA = JsonParser.parseString("""
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["id", "count", "entries"],
              "properties": {
                "id": {"type": "string"},
                "count": {"type": "integer", "minimum": 0, "maximum": 10},
                "kind": {"enum": ["block", "item"]},
                "entries": {"type": "array", "items": {"type": "object", "required": ["name"],
                            "properties": {"name": {"type": "string"}}}}
              }
            }
            """).getAsJsonObject();

    private static ValidationResult validate(String document) {
        ErrorCollector errors = new ErrorCollector(64);
        JsonElement parsed = JsonParser.parseString(document);
        return new SchemaValidator(SCHEMA).validate(parsed, "subject.json",
                new ValidationContext("subject.json", errors));
    }

    @Test
    void acceptsAConformingDocument() {
        ValidationResult result = validate("""
                {"id": "minecraft:stone", "count": 3, "kind": "block",
                 "entries": [{"name": "a"}, {"name": "b"}]}
                """);

        assertTrue(result.isValid(), "unexpected issues: " + result.issues());
    }

    @Test
    void reportsMissingRequiredFields() {
        ValidationResult result = validate("{\"id\": \"x\"}");

        List<String> types = result.issues().stream().map(ExtractionIssue::type).toList();
        assertEquals(2, types.stream().filter("MISSING_FIELD"::equals).count(),
                "count and entries are both required: " + result.issues());
        assertTrue(types.contains("MISSING_FIELD"));
    }

    @Test
    void rejectsAWrongTypeAtTheRoot() {
        ValidationResult result = validate("[]");

        assertFalse(result.isValid());
        assertEquals("SCHEMA_TYPE", result.issues().get(0).type());
        assertEquals("$", result.issues().get(0).entry());
    }

    @Test
    void rejectsAWrongTypeInAProperty() {
        ValidationResult result = validate("{\"id\": 5, \"count\": 1, \"entries\": []}");

        assertEquals(1, result.errors().size(), "issues: " + result.issues());
        assertEquals("$.id", result.errors().get(0).entry());
    }

    /** An integer schema must reject a fraction, which a plain "number" check would let through. */
    @Test
    void rejectsAFractionWhereAnIntegerIsRequired() {
        assertFalse(validate("{\"id\": \"x\", \"count\": 1.5, \"entries\": []}").isValid());
        assertTrue(validate("{\"id\": \"x\", \"count\": 1.0, \"entries\": []}").isValid(),
                "1.0 is an integral number and must be accepted");
    }

    @Test
    void enforcesNumericBounds() {
        List<ExtractionIssue> below = validate("{\"id\": \"x\", \"count\": -1, \"entries\": []}").issues();
        List<ExtractionIssue> above = validate("{\"id\": \"x\", \"count\": 11, \"entries\": []}").issues();

        assertEquals("OUT_OF_RANGE", below.get(0).type());
        assertEquals("$.count", below.get(0).entry());
        assertEquals("OUT_OF_RANGE", above.get(0).type());
        assertTrue(validate("{\"id\": \"x\", \"count\": 10, \"entries\": []}").isValid(),
                "the upper bound is inclusive");
    }

    @Test
    void enforcesEnumMembers() {
        assertTrue(validate("{\"id\": \"x\", \"count\": 1, \"kind\": \"item\", \"entries\": []}").isValid());

        List<ExtractionIssue> issues = validate("{\"id\": \"x\", \"count\": 1, \"kind\": \"chest\", \"entries\": []}")
                .issues();
        assertEquals("SCHEMA_ENUM", issues.get(0).type());
    }

    /** A closed object has to reject an unexpected member, which is what catches a field rename. */
    @Test
    void rejectsUnexpectedPropertiesOnAClosedObject() {
        List<ExtractionIssue> issues = validate(
                "{\"id\": \"x\", \"count\": 1, \"entries\": [], \"material\": \"stone\"}").issues();

        assertEquals(1, issues.size());
        assertEquals("UNKNOWN_PROPERTY", issues.get(0).type());
        assertFalse(result(issues).isValid());
    }

    @Test
    void walksArrayItemsAndReportsTheIndex() {
        List<ExtractionIssue> issues = validate(
                "{\"id\": \"x\", \"count\": 1, \"entries\": [{\"name\": \"ok\"}, {}]}").issues();

        assertEquals(1, issues.size());
        assertEquals("$.entries[1]", issues.get(0).entry());
        assertEquals("name", issues.get(0).field());
    }

    /** With no "type" constraint the validator still descends into nested objects. */
    @Test
    void descendsIntoNestedObjectsWithoutATypeConstraint() {
        JsonObject schema = JsonParser.parseString("""
                {"properties": {"outer": {"properties": {"inner": {"type": "boolean"}}}}}
                """).getAsJsonObject();

        ErrorCollector errors = new ErrorCollector(64);
        ValidationContext context = new ValidationContext("x", errors);
        new SchemaValidator(schema).validate(JsonParser.parseString("{\"outer\": {\"inner\": 3}}"),
                "subject", context);

        assertEquals(1, context.issues().size());
        assertEquals("$.outer.inner", context.issues().get(0).entry());
    }

    @Test
    void reportsEveryProblemRatherThanStoppingAtTheFirst() {
        ValidationResult result = validate("{\"id\": 1, \"count\": \"many\", \"entries\": {}}");

        assertEquals(3, result.errors().size(), "issues: " + result.issues());
    }

    /** Issues go to both the local list and the shared sink, so a report sees them once. */
    @Test
    void forwardsIssuesToTheSharedCollector() {
        ErrorCollector errors = new ErrorCollector(64);
        new SchemaValidator(SCHEMA).validate(JsonParser.parseString("{}"), "subject.json",
                new ValidationContext("subject.json", errors));

        assertEquals(3, errors.issues().size());
        assertEquals(3, errors.errorCount());
        assertEquals(0, errors.warningCount());
    }

    private static ValidationResult result(List<ExtractionIssue> issues) {
        return new ValidationResult("subject.json", issues);
    }
}
