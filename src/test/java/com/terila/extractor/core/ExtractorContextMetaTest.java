package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the metadata every document carries.
 *
 * <p>The timestamp is the part that used to be wrong: each extractor asked the clock when it wrote its
 * document, so the documents of a single run disagreed by seconds depending on which dependency level
 * they ran in. One run now stamps everything with the same instant.
 */
class ExtractorContextMetaTest {

    private static final Path OUTPUT = Path.of("build", "test-output", "meta");

    @BeforeAll
    static void bootStrap() {
        // _meta reads the game's version constants, which need the game's own bootstrap.
        TestSupport.bootStrap();
    }

    @Test
    void everyDocumentOfARunSharesOneTimestamp() {
        Instant runStartedAt = Instant.parse("2026-01-02T03:04:05Z");
        ExtractorContext context = context(runStartedAt);

        JsonObject first = context.meta("blocks", 1);
        JsonObject second = context.meta("block_shapes", 2);

        assertEquals(runStartedAt.toString(), first.get("generated_at").getAsString());
        assertEquals(first.get("generated_at").getAsString(), second.get("generated_at").getAsString(),
                "two extractors of the same run reported different timestamps");
    }

    @Test
    void aManuallyBuiltContextStillStampsOneInstant() {
        ExtractorContext context = ExtractorContext.builder()
                .config(ExtractorConfig.builder(OUTPUT).build())
                .writer(JsonWriterFactory.pretty(OUTPUT))
                .build();

        String first = context.meta("blocks", 1).get("generated_at").getAsString();
        String second = context.meta("blocks", 1).get("generated_at").getAsString();

        assertNotNull(first);
        assertEquals(first, second);
    }

    private static ExtractorContext context(Instant generatedAt) {
        return ExtractorContext.builder()
                .config(ExtractorConfig.builder(OUTPUT).build())
                .writer(JsonWriterFactory.pretty(OUTPUT))
                .generatedAt(generatedAt)
                .build();
    }
}
