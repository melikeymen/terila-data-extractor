package com.terila.extractor.extractors.misc;

// Written by AI
import com.terila.extractor.core.DataType;
import com.terila.extractor.extractors.registry.CodecRegistryDumpExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.SulfurCubeArchetype;

/**
 * Writes {@code worldgen/sulfur_cube_archetypes.json}.
 *
 * <p><strong>This is vanilla data, not a Terila addition.</strong> The question comes up because
 * nothing named "sulfur cube" exists in Minecraft releases before 26.2, so the entry can look like a
 * leftover from a mod or a guess. The evidence that it is real:
 *
 * <ul>
 *   <li>{@link Registries#SULFUR_CUBE_ARCHETYPE} and {@link SulfurCubeArchetype} are part of the
 *       official server jar this project compiles against, so this class would not build without
 *       them;</li>
 *   <li>the registry appears in the game's own registry list, which is why
 *       {@code registries/index.json} reports it as a data pack registry and
 *       {@code registries/dump/minecraft/sulfur_cube_archetype.json} dumps it with the other
 *       registered registries;</li>
 *   <li>the vanilla data pack ships the files at
 *       {@code data/minecraft/sulfur_cube_archetype/<path>.json}, so the entries are read back with
 *       full payloads;</li>
 *   <li>the game's own data generator reports it, so {@code registries/report.json} lists a protocol
 *       id for it.</li>
 * </ul>
 *
 * <p>It is kept as a dedicated extractor (rather than relying on the generic registry dump) because
 * the design calls for it under {@code worldgen/} next to the other world generation data, and
 * because the curator deciding what to extract should be able to see the addition explicitly.
 *
 * <p>If a future release removes the registry, this extractor is the one place that fails to compile,
 * which is the intended signal.
 */
public final class SulfurCubeArchetypeExtractor
        extends CodecRegistryDumpExtractor<SulfurCubeArchetype> {

    public SulfurCubeArchetypeExtractor() {
        super("sulfur_cube_archetypes", DataType.SULFUR_CUBE_ARCHETYPES,
                "worldgen/sulfur_cube_archetypes.json", Registries.SULFUR_CUBE_ARCHETYPE);
    }
}
