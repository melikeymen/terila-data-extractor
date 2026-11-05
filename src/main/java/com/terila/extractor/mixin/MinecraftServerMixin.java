package com.terila.extractor.mixin;

// Written by AI
import com.terila.extractor.TerilaExtractor;
import com.terila.extractor.core.ExtractionReport;
import com.terila.extractor.core.ExtractionRunner;
import com.terila.extractor.core.ExtractorConfig;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.FunctionTagSource;
import com.terila.extractor.core.ResourcePackReader;
import com.terila.extractor.extractors.Extractors;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Triggers the extraction pipeline once the dedicated server has fully prepared its levels.
 *
 * <p>By that point the frozen {@code RegistryAccess} (including every datapack registry), the tag
 * bindings, the recipe manager and the command dispatcher are all available — this is the only path
 * that can produce worldgen, recipe and command data.
 *
 * <p>Extraction is opt-in so a normal server start is never slowed down:
 * {@code -Dterila.extractor.enabled=true}. The output directory can be set with
 * {@code -Dterila.extractor.output=<dir>}.
 *
 * <p>The data generator reports and the client jar are discovered like they are for the standalone
 * tool, so pointing {@code -Dterila.reports.dir} / {@code -Dterila.minecraft.clientJar} at them (or
 * running from the project directory) gives the in-game path the same coverage.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "prepareLevels", at = @At("RETURN"))
    private void terila$afterPrepareLevels(CallbackInfo ci) {
        if (!Boolean.getBoolean("terila.extractor.enabled")) {
            return;
        }

        MinecraftServer server = (MinecraftServer) (Object) this;
        Path output = Path.of(System.getProperty("terila.extractor.output", "extractor_output"));

        try {
            ExtractorConfig config = ExtractorConfig.builder(output)
                    .verbose(Boolean.getBoolean("terila.extractor.verbose"))
                    .build();

            ExtractorContext.Builder context = ExtractorContext.builder()
                    .registryAccess(server.registryAccess())
                    .recipeManager(server.getRecipeManager())
                    .commands(server.getCommands())
                    .reloadableRegistries(server.reloadableRegistries())
                    .functionTags(FunctionTagSource.of(server.getFunctions()))
                    // The server's resource manager reaches every active pack, so data without a
                    // registry (advancements, loot tables, predicates, item modifiers) is read from
                    // the world's own datapacks rather than from a --datapack directory. The NBT
                    // structure templates are resources too, so they need it as well.
                    .pack(ResourcePackReader.of(server.getResourceManager()))
                    .resources(server.getResourceManager());

            ExtractionReport report = ExtractionRunner.run(config, Extractors.defaultRegistry(), context);
            TerilaExtractor.LOGGER.info("[Terila Extractor] {} of {} extractors, {} entries",
                    report.successfulExtractors(), report.results().size(), report.totalEntries());
        } catch (Exception failure) {
            TerilaExtractor.LOGGER.error("[Terila Extractor] extraction failed", failure);
        }
    }
}

