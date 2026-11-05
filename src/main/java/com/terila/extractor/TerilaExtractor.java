package com.terila.extractor;

// Written by AI
import com.terila.extractor.core.ExtractorVersion;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric entrypoint of the Terila Data Extractor.
 *
 * <p>In-game the extraction pipeline is triggered from {@code MinecraftServerMixin} once the server
 * has finished preparing its levels (registries, tags, recipes and commands are all populated by
 * then). Enable it with {@code -Dterila.extractor.enabled=true}.
 *
 * <p>For head-less runs see {@code com.terila.extractor.bootstrap.StandaloneExtractor}.
 */
public final class TerilaExtractor implements ModInitializer, DedicatedServerModInitializer {

    public static final String MOD_ID = "terila-extractor";
    public static final Logger LOGGER = LoggerFactory.getLogger("TerilaExtractor");

    @Override
    public void onInitialize() {
        LOGGER.info("[Terila Extractor] {} ready for {}", ExtractorVersion.VERSION, ExtractorVersion.describe());
    }

    @Override
    public void onInitializeServer() {
        LOGGER.info("[Terila Extractor] server entrypoint ready (extraction is opt-in via "
                + "-Dterila.extractor.enabled=true)");
    }
}
