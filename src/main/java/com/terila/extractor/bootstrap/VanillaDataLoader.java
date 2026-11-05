package com.terila.extractor.bootstrap;

// Written by AI
import com.terila.extractor.core.ExtractorLogger;
import com.terila.extractor.core.FunctionTagSource;
import com.terila.extractor.core.PackReader;
import com.terila.extractor.core.ResourcePackReader;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.RecipeManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Loads the vanilla data set head-less — the same data a running server sees.
 *
 * <p>Without this, a head-less run only ever saw the built-in (static) registries: biomes,
 * enchantments, damage types, trims, dialogs, variants, recipes, tags and commands were all empty,
 * because the server normally loads them from the data packs. This class replays the exact load
 * sequence {@code net.minecraft.server.WorldLoader} uses, in the same order, against the vanilla data
 * pack that ships inside the server jar:
 *
 * <ol>
 *   <li>static layer: {@link RegistryLayer#createRegistryAccess()} over the built-in registries,</li>
 *   <li>static tags: {@link TagLoader#loadTagsForExistingRegistries},</li>
 *   <li>world layer (worldgen in 26.2): {@link RegistryDataLoader#WORLD_REGISTRIES},</li>
 *   <li>dimension layer: {@link RegistryDataLoader#DIMENSION_REGISTRIES},</li>
 *   <li>reloadable layer: {@link ReloadableServerRegistries#reload},</li>
 *   <li>recipes, commands, advancements and data components:
 *       {@link ReloadableServerResources#loadResources} plus
 *       {@link ReloadableServerResources#updateComponentsAndStaticRegistryTags()}.</li>
 * </ol>
 *
 * <p>An optional datapack directory is stacked on top of the vanilla pack, so the extractor can also
 * dump a custom data pack with the same coverage. Everything registered here is already frozen and
 * safe to read from the extractor's worker threads.
 *
 * <p>The whole sequence takes a couple of seconds and needs no game, level or server instance.
 */
public final class VanillaDataLoader implements AutoCloseable {

    private final CloseableResourceManager resources;
    private final RegistryAccess.Frozen registryAccess;
    private final LayeredRegistryAccess<RegistryLayer> layers;
    private final ReloadableServerRegistries.Holder reloadableRegistries;
    private final RecipeManager recipeManager;
    private final Commands commands;
    private final PackReader pack;
    private final FunctionTagSource functionTags;

    private VanillaDataLoader(CloseableResourceManager resources, RegistryAccess.Frozen registryAccess,
                              LayeredRegistryAccess<RegistryLayer> layers,
                              ReloadableServerRegistries.Holder reloadableRegistries,
                              RecipeManager recipeManager, Commands commands,
                              FunctionTagSource functionTags) {
        this.resources = resources;
        this.registryAccess = registryAccess;
        this.layers = layers;
        this.reloadableRegistries = reloadableRegistries;
        this.recipeManager = recipeManager;
        this.commands = commands;
        this.pack = ResourcePackReader.of(resources);
        this.functionTags = functionTags;
    }

    /**
     * Loads every data pack (vanilla, plus {@code datapackRoot} when given) and the server data
     * derived from them.
     *
     * @param datapackRoot optional extra data pack directory, stacked on top of the vanilla pack.
     * @param log          destination for progress and timing lines.
     */
    public static VanillaDataLoader load(Path datapackRoot, ExtractorLogger log) {
        return loadPacks(datapackRoot == null ? List.of() : List.of(datapackRoot), log);
    }

    /**
     * Loads the vanilla pack plus several datapacks, stacked in list order.
     *
     * <p>Later packs win on conflicts, exactly as they do in a server's pack repository. The extra
     * overload is also what lets tests exercise tag merging and {@code replace} across packs without
     * having to manually combine their files.
     *
     * @param datapackRoots extra data pack directories, lowest priority first.
     * @param log           destination for progress and timing lines.
     */
    public static VanillaDataLoader loadPacks(List<Path> datapackRoots, ExtractorLogger log) {
        Executor inline = Runnable::run;
        long start = System.nanoTime();

        CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                packs(datapackRoots, log));

        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> pendingTags =
                TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
        log.debug("loaded %d static tag set(s), %d tag(s)", pendingTags.size(),
                pendingTags.stream().mapToInt(Registry.PendingTags::size).sum());

        List<HolderLookup.RegistryLookup<?>> worldgenContext =
                TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), pendingTags);
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, worldgenContext,
                RegistryDataLoader.WORLD_REGISTRIES, inline).join();

        List<HolderLookup.RegistryLookup<?>> dimensionContext =
                Stream.concat(worldgenContext.stream(), worldgen.listRegistries()).toList();
        RegistryAccess.Frozen dimensions = RegistryDataLoader.load(resources, dimensionContext,
                RegistryDataLoader.DIMENSION_REGISTRIES, inline).join();

        layers = layers.replaceFrom(RegistryLayer.WORLD, worldgen)
                .replaceFrom(RegistryLayer.DIMENSIONS, dimensions);
        long dataLoaded = System.nanoTime();

        // Everything below is what a server reload does: loot tables, enchantments, damage types,
        // trims, variants, dialogs, ... plus recipes, advancements, commands and data components.
        ReloadableServerRegistries.LoadResult reloadable =
                ReloadableServerRegistries.reload(layers, pendingTags, resources, inline).join();
        // VANILLA_SET / DEDICATED / ALL_PERMISSIONS: a normal server's view, so the command tree and
        // recipes come out complete rather than filtered by the environment.
        ReloadableServerResources serverResources = ReloadableServerResources.loadResources(
                resources, reloadable.layers(), pendingTags, FeatureFlags.VANILLA_SET,
                Commands.CommandSelection.DEDICATED, PermissionSet.ALL_PERMISSIONS, inline, inline).join();
        serverResources.updateComponentsAndStaticRegistryTags();
        long serverLoaded = System.nanoTime();

        RegistryAccess.Frozen access = reloadable.layers().compositeAccess();
        log.info("loaded %d registries, %d recipes, %d commands in %s (data %s / server %s)",
                access.listRegistryKeys().count(), serverResources.getRecipeManager().getRecipes().size(),
                serverResources.getCommands().getDispatcher().getRoot().getChildren().size(),
                millis(start, System.nanoTime()),
                millis(start, dataLoaded), millis(dataLoaded, serverLoaded));

        return new VanillaDataLoader(resources, access, reloadable.layers(),
                serverResources.fullRegistries(), serverResources.getRecipeManager(),
                serverResources.getCommands(),
                FunctionTagSource.of(serverResources.getFunctionLibrary()));
    }

    /** The packs to load: vanilla first, then each datapack in order so later packs win. */
    private static List<PackResources> packs(List<Path> datapackRoots, ExtractorLogger log) {
        List<PackResources> packs = new ArrayList<>();
        // 26.3 split the vanilla pack into a wrapper (VanillaPackResources) plus the pack itself.
        packs.add(ServerPacksSource.createVanillaPackSource().fullResources());

        if (datapackRoots == null) {
            return packs;
        }

        for (int i = 0; i < datapackRoots.size(); i++) {
            Path candidate = datapackRoots.get(i);
            if (candidate == null) {
                continue;
            }
            Path root = candidate.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                log.warn("datapack %s is not a directory; it was skipped", root);
                continue;
            }

            String packId = "terila-datapack-" + (i + 1);
            PackLocationInfo location = new PackLocationInfo(packId,
                    Component.literal(root.toString()), PackSource.DEFAULT, Optional.empty());
            packs.add(new PathPackResources(location, root));
            log.info("datapack overlay [%d] -> %s", i + 1, root);
        }
        return packs;
    }

    private static String millis(long from, long to) {
        return String.format(Locale.ROOT, "%.2fs", (to - from) / 1_000_000_000.0);
    }

    /** Every registry of the loaded data set, including all datapack registries. */
    public RegistryAccess.Frozen registryAccess() {
        return registryAccess;
    }

    /**
     * The layered registry access the data set was loaded into.
     *
     * <p>Vanilla's {@code TagNetworkSerialization.serializeTagsToNetwork} takes this type; it is what
     * lets the extractor ask the game which registries its Update Tags packet would carry.
     */
    public LayeredRegistryAccess<RegistryLayer> layers() {
        return layers;
    }

    /**
     * Full registry holder, with updated tags.
     *
     * <p>Its {@code lookup()} is the tag-aware provider the extractors serialise codecs against, so
     * {@code #tag} references and nested registry entries resolve instead of failing.
     */
    public ReloadableServerRegistries.Holder reloadableRegistries() {
        return reloadableRegistries;
    }

    public RecipeManager recipeManager() {
        return recipeManager;
    }

    /** The command dispatcher as the server would build it (vanilla commands included). */
    public Commands commands() {
        return commands;
    }

    /** Raw file access to the loaded packs, for data that is not a registry. */
    public PackReader pack() {
        return pack;
    }

    /** The effective function tags after the game merged and flattened every loaded pack. */
    public FunctionTagSource functionTags() {
        return functionTags;
    }

    /**
     * The resource manager over every loaded pack.
     *
     * <p>Registries cover most data, but the vanilla data pack also ships NBT structure templates
     * that no registry exposes; they are only reachable as resources.
     */
    public ResourceManager resources() {
        return resources;
    }

    @Override
    public void close() {
        resources.close();
    }
}

