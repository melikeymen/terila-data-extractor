package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonObject;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Everything an extractor may need for one run.
 *
 * <p>The game-side handles are all optional: the standalone bootstrap exposes the built-in
 * registries without a world, while the in-game entrypoint additionally supplies recipes, commands
 * and the reloadable (datapack driven) registries.
 */
public final class ExtractorContext {

    private final ExtractorConfig config;
    private final JsonWriter writer;
    private final ExtractorLogger log;
    private final ErrorCollector errors;
    private final RegistryAccess.Frozen registryAccess;
    private final RecipeManager recipeManager;
    private final Commands commands;
    private final ReloadableServerRegistries.Holder reloadableRegistries;
    private final LayeredRegistryAccess<RegistryLayer> layers;
    private final FunctionTagSource functionTags;
    private final PackReader pack;
    private final Path datapackRoot;
    private final Path clientJar;
    private final Path languagesDir;
    private final Path reportsRoot;
    private final ReportReader reports;
    private final ResourceManager resources;
    private final Map<Class<?>, Object> cache = new ConcurrentHashMap<>();

    /** Slow-path guard for {@link #shared}; only ever contended on a cache miss. */
    private final Object cacheLock = new Object();

    private ExtractorContext(Builder builder) {
        this.config = builder.config;
        this.writer = builder.writer;
        this.log = builder.log;
        this.errors = builder.errors;
        this.registryAccess = builder.registryAccess != null ? builder.registryAccess : RegistryAccess.EMPTY;
        this.recipeManager = builder.recipeManager;
        this.commands = builder.commands;
        this.reloadableRegistries = builder.reloadableRegistries;
        this.layers = builder.layers;
        this.functionTags = builder.functionTags;
        this.pack = builder.pack;
        this.datapackRoot = builder.datapackRoot;
        this.clientJar = builder.clientJar;
        this.languagesDir = builder.languagesDir;
        this.reportsRoot = builder.reportsRoot;
        this.reports = builder.reportsRoot != null ? ReportReader.of(builder.reportsRoot) : null;
        this.resources = builder.resources;
    }

    /** The run's configuration (thread count, precision, enabled categories). */
    public ExtractorConfig config() {
        return config;
    }

    /** The writer every document goes through. */
    public JsonWriter writer() {
        return writer;
    }

    /** The run's logger. */
    public ExtractorLogger log() {
        return log;
    }

    /**
     * @return the collector extractors report problems to; a failure recorded here shows up in
     *         {@code _errors.json} rather than aborting the run.
     */
    public ErrorCollector errors() {
        return errors;
    }

    /** The frozen registry access the loaded data set was built with. */
    public RegistryAccess.Frozen registryAccess() {
        return registryAccess;
    }

    /**
     * @param <T> element type the registry holds.
     * @param key registry to look up.
     * @return the registry when this environment has it loaded.
     */
    public <T> Optional<Registry<T>> registry(ResourceKey<Registry<T>> key) {
        return registryAccess.lookup(key);
    }

    /**
     * Lookup provider codecs are serialised against.
     *
     * <p>Deliberately the composite registry access rather than the reloadable holder's own lookup:
     * the composite access spans every layer, so a reference to <em>any</em> registry resolves while
     * encoding. 26.3 moved recipes, loot tables, predicates and item modifiers into registries, and
     * the reloadable lookup does not cover them — encoding an advancement that references a recipe,
     * or a loot table that references a predicate, fails with "is not valid in current registry set"
     * and the entry loses its payload. With the composite access every entry encodes.
     *
     * <p>Tags still resolve, because the registries of a loaded data set have their tags bound.
     */
    public HolderLookup.Provider lookup() {
        return registryAccess;
    }

    /** Recipe manager of an in-game server; empty when the loader did not build recipes. */
    public Optional<RecipeManager> recipeManager() {
        return Optional.ofNullable(recipeManager);
    }

    /** Live command dispatcher; only the in-game entry point can supply one. */
    public Optional<Commands> commands() {
        return Optional.ofNullable(commands);
    }

    /** Reloadable (data-pack driven) registries; empty in the built-in-only headless path. */
    public Optional<ReloadableServerRegistries.Holder> reloadableRegistries() {
        return Optional.ofNullable(reloadableRegistries);
    }

    /**
     * The layered registry access the data set was loaded into.
     *
     * <p>Vanilla's {@code TagNetworkSerialization.serializeTagsToNetwork} takes this type, so it is
     * the only way to ask the game which registries its own Update Tags packet would carry. Absent
     * in a manually constructed context, where that diagnostic is simply skipped.
     */
    public Optional<LayeredRegistryAccess<RegistryLayer>> layers() {
        return Optional.ofNullable(layers);
    }

    /**
     * The game's effective function tags, after pack merging and nested-tag resolution.
     *
     * <p>Raw tag files are still available through {@link #pack()}; this view is the one that matches
     * what {@code /function #tag} would execute. It is absent in a manually constructed context that
     * did not load the server resources.
     */
    public Optional<FunctionTagSource> functionTags() {
        return Optional.ofNullable(functionTags);
    }

    /** Root of the vanilla datapack, when one was located; used for raw JSON fallbacks. */
    public Optional<Path> datapackRoot() {
        return Optional.ofNullable(datapackRoot);
    }

    /**
     * Reader for pack files that are not registries: advancements, loot tables, predicates, item
     * modifiers, function tags and {@code .mcfunction} sources.
     *
     * <p>Preferred is an explicitly supplied reader — the head-less loader passes one backed by the
     * loaded data packs. Without one, a {@code --datapack} directory is used when configured.
     * Empty when neither is available, in which case those extractors report a warning.
     */
    public Optional<PackReader> pack() {
        if (pack != null) {
            return pack.isAvailable() ? Optional.of(pack) : Optional.empty();
        }
        return Optional.ofNullable(datapackRoot)
                .<PackReader>map(DatapackReader::of)
                .filter(PackReader::isAvailable);
    }

    /**
     * The Minecraft client jar, when located.
     *
     * <p>Language files and the whole resource pack only ship in the client jar. When the caller did
     * not name one, the well known build locations are searched — that is what makes a plain
     * {@code ./gradlew runExtractor} and the in-game entrypoint cover assets too.
     */
    public Optional<Path> clientJar() {
        return Optional.ofNullable(clientJar).or(() -> InputLocator.clientJar());
    }

    /**
     * The directory holding every locale as {@code <locale>.json}, when located.
     *
     * <p>{@code ./gradlew downloadLanguages} fills {@code .mc-cache/lang}; the client jar only ships
     * {@code en_us.json}. When the directory is absent the extractor falls back to the single locale
     * inside the client jar and says so in {@code translations/locales.json}.
     */
    public Optional<Path> languagesDir() {
        return Optional.ofNullable(languagesDir).or(() -> InputLocator.languagesDir());
    }

    /**
     * The vanilla data-generator reports directory, when located.
     *
     * <p>Packet ids are not exposed by any runtime API; the game writes them to
     * {@code reports/packets.json}, which this extractor reads when present.
     */
    public Optional<Path> reportsRoot() {
        return Optional.ofNullable(reportsRoot).or(() -> InputLocator.reportsRoot());
    }

    /**
     * Reader for the data generator reports ({@code packets.json}, {@code blocks.json},
     * {@code registries.json}, {@code commands.json}, the per-item component reports, ...).
     *
     * <p>Empty when no reports directory was located, in which case the report-driven extractors
     * report a warning instead of writing an empty document.
     */
    public Optional<ReportReader> reports() {
        if (reports != null && reports.isAvailable()) {
            return Optional.of(reports);
        }
        return reportsRoot().map(ReportReader::of).filter(ReportReader::isAvailable);
    }

    /**
     * The loaded data packs, for data that is neither a registry nor JSON.
     *
     * <p>Structure templates are NBT files inside the vanilla data pack, so they can only be read
     * through the resource manager that loaded the packs.
     */
    public Optional<ResourceManager> resources() {
        return Optional.ofNullable(resources);
    }

    /**
     * Lazily built, per-run shared value (e.g. an expensive registry view computed once).
     *
     * <p>Reads take no lock at all once the value exists, which matters because the block and item
     * catalogs are read by every extractor in their dependency level. A miss is resolved under a
     * single dedicated monitor rather than {@code ConcurrentHashMap.computeIfAbsent}: the factory
     * may itself ask for another shared value, and the map's own compute methods forbid that
     * reentrancy. Holding one short-lived monitor keeps the guarantee that the factory runs exactly
     * once per key, which matters because the values are catalogs other extractors then read.
     *
     * @param <T>     type of the shared value.
     * @param key     the type identifying the value; distinct keys never block each other's factories
     *                beyond the brief publish step.
     * @param factory invoked at most once for this key.
     * @return the cached value, building it on first use.
     */
    @SuppressWarnings("unchecked")
    public <T> T shared(Class<T> key, Supplier<T> factory) {
        Object cached = cache.get(key);
        if (cached != null) {
            return (T) cached;
        }
        synchronized (cacheLock) {
            Object value = cache.get(key);
            if (value == null) {
                value = factory.get();
                cache.put(key, value);
            }
            return (T) value;
        }
    }

    /**
     * Standard {@code _meta} block attached to every extracted document.
     *
     * @param extractorName name recorded as the document's producer.
     * @param entryCount    how many entries the document carries.
     * @return the metadata block, including the version triple and a timestamp.
     */
    public JsonObject meta(String extractorName, int entryCount) {
        JsonObject meta = new JsonObject();
        meta.addProperty("extractor_version", ExtractorVersion.VERSION);
        meta.addProperty("minecraft_version", ExtractorVersion.MINECRAFT_VERSION);
        meta.addProperty("protocol_version", ExtractorVersion.PROTOCOL_VERSION);
        meta.addProperty("data_pack_version", ExtractorVersion.DATA_PACK_VERSION);
        meta.addProperty("world_version", ExtractorVersion.WORLD_VERSION);
        meta.addProperty("generated_at", Instant.now().toString());
        meta.addProperty("source", "minecraft:vanilla");
        meta.addProperty("extractor_name", extractorName);
        meta.addProperty("entry_count", entryCount);
        return meta;
    }

    /** Starts a context builder with a default config and logger, ready for tests or a loader. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link ExtractorContext}; each setter returns {@code this} for chaining. */
    public static final class Builder {

        private ExtractorConfig config = ExtractorConfig.builder().build();
        private JsonWriter writer;
        private ExtractorLogger log = ExtractorLogger.scoped("Extractor");
        private ErrorCollector errors = new ErrorCollector();
        private RegistryAccess.Frozen registryAccess;
        private RecipeManager recipeManager;
        private Commands commands;
        private ReloadableServerRegistries.Holder reloadableRegistries;
        private LayeredRegistryAccess<RegistryLayer> layers;
        private FunctionTagSource functionTags;
        private PackReader pack;
        private Path datapackRoot;
        private Path clientJar;
        private Path languagesDir;
        private Path reportsRoot;
        private ResourceManager resources;

        /** Uses the supplied run configuration and, later, its output writer. */
        public Builder config(ExtractorConfig config) {
            this.config = config;
            return this;
        }

        /** Overrides the writer; otherwise {@link #build()} creates one from the configuration. */
        public Builder writer(JsonWriter writer) {
            this.writer = writer;
            return this;
        }

        /** Supplies the logger every extractor in this run shares. */
        public Builder log(ExtractorLogger log) {
            this.log = log;
            return this;
        }

        /** Supplies the collector that receives non-fatal extraction issues. */
        public Builder errors(ErrorCollector errors) {
            this.errors = errors;
            return this;
        }

        /** Supplies the frozen registry access loaded by the game. */
        public Builder registryAccess(RegistryAccess.Frozen registryAccess) {
            this.registryAccess = registryAccess;
            return this;
        }

        /** Adds the loaded recipes for recipe and advancement encoding. */
        public Builder recipeManager(RecipeManager recipeManager) {
            this.recipeManager = recipeManager;
            return this;
        }

        /** Adds the live command dispatcher for command-tree extraction. */
        public Builder commands(Commands commands) {
            this.commands = commands;
            return this;
        }

        /** Adds the reloadable registries that hold data-pack-driven content. */
        public Builder reloadableRegistries(ReloadableServerRegistries.Holder reloadableRegistries) {
            this.reloadableRegistries = reloadableRegistries;
            return this;
        }

        /** Adds the layered registry access the data set was loaded into. */
        public Builder layers(LayeredRegistryAccess<RegistryLayer> layers) {
            this.layers = layers;
            return this;
        }

        /** Adds the game's merged function-tag view. */
        public Builder functionTags(FunctionTagSource functionTags) {
            this.functionTags = functionTags;
            return this;
        }

        /** Supplies an already loaded pack reader; useful when the caller owns the resource manager. */
        public Builder pack(PackReader pack) {
            this.pack = pack;
            return this;
        }

        /** Fallback data-pack directory used only when no {@link PackReader} is supplied. */
        public Builder datapackRoot(Path datapackRoot) {
            this.datapackRoot = datapackRoot;
            return this;
        }

        /** Adds the client jar for language files and resource-pack assets. */
        public Builder clientJar(Path clientJar) {
            this.clientJar = clientJar;
            return this;
        }

        /** Adds the directory of per-locale JSON files downloaded from Mojang. */
        public Builder languagesDir(Path languagesDir) {
            this.languagesDir = languagesDir;
            return this;
        }

        /** Adds the data-generator reports directory. */
        public Builder reportsRoot(Path reportsRoot) {
            this.reportsRoot = reportsRoot;
            return this;
        }

        /** Adds the game resource manager used for NBT structure templates. */
        public Builder resources(ResourceManager resources) {
            this.resources = resources;
            return this;
        }

        /** Creates the immutable context, filling in a writer when none was supplied. */
        public ExtractorContext build() {
            if (writer == null) {
                writer = JsonWriterFactory.create(config.outputDirectory(), config);
            }
            return new ExtractorContext(this);
        }
    }
}

