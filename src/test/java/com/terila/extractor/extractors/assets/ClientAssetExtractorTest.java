package com.terila.extractor.extractors.assets;

// Written by AI
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keeps the client jar provenance in the asset documents independent of how the path was passed. */
class ClientAssetExtractorTest {

    @Test
    void projectRelativeJarStaysRelative() {
        Path jar = Path.of(".mc-cache", "minecraft_client.26.3.jar");

        assertEquals(jar.toString(), ClientAssetExtractor.sourcePath(jar));
    }

    @Test
    void jarOutsideTheProjectKeepsANormalisedAbsolutePath() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path jar = workingDirectory.getParent().resolve("terila-outside-client.jar");

        assertEquals(jar.toAbsolutePath().normalize().toString(),
                ClientAssetExtractor.sourcePath(jar));
    }
}

