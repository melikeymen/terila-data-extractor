package com.terila.extractor.core;

// Written by AI
import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;

/**
 * Version metadata for the extractor and the game it is reading from.
 *
 * <p>Everything is read from the running game rather than declared by hand, so a Minecraft version
 * bump is reflected automatically: the world version, the network protocol, the data pack format and
 * the resource pack format are all properties of the detected {@link WorldVersion}.
 *
 * <p>The formats come from {@link WorldVersion#packVersion(PackType)} rather than the
 * {@code SharedConstants.DATA_PACK_FORMAT_*} / {@code RESOURCE_PACK_FORMAT_*} constants, which are
 * deprecated for exactly this reason: they only describe the newest format, while {@code packVersion}
 * reports what the detected version actually uses.
 */
public final class ExtractorVersion {

    /** Version of the extractor itself; bump on output-format changes. */
    public static final String VERSION = "1.0.0-beta.1+26.3";

    private static final WorldVersion WORLD = SharedConstants.getCurrentVersion();

    public static final String MINECRAFT_VERSION = WORLD.name();
    public static final int PROTOCOL_VERSION = WORLD.protocolVersion();
    public static final int WORLD_VERSION = WORLD.dataVersion().version();

    /** Data pack (server data) format of the detected version. */
    private static final PackFormat DATA_PACK_FORMAT = WORLD.packVersion(PackType.SERVER_DATA);

    /** Resource pack (client assets) format of the detected version. */
    private static final PackFormat RESOURCE_PACK_FORMAT = WORLD.packVersion(PackType.CLIENT_RESOURCES);

    public static final int DATA_PACK_VERSION_MAJOR = DATA_PACK_FORMAT.major();
    public static final int DATA_PACK_VERSION_MINOR = DATA_PACK_FORMAT.minor();
    public static final String DATA_PACK_VERSION =
            DATA_PACK_VERSION_MAJOR + "." + DATA_PACK_VERSION_MINOR;

    public static final int RESOURCE_PACK_VERSION_MAJOR = RESOURCE_PACK_FORMAT.major();
    public static final int RESOURCE_PACK_VERSION_MINOR = RESOURCE_PACK_FORMAT.minor();
    public static final String RESOURCE_PACK_VERSION =
            RESOURCE_PACK_VERSION_MAJOR + "." + RESOURCE_PACK_VERSION_MINOR;

    /**
     * Schema version of the data generator's own report files.
     *
     * <p>Not exposed by {@link WorldVersion}, and the constant is the only way to ask for it, so this
     * is the one value that has to come from {@link SharedConstants}.
     */
    @SuppressWarnings("deprecation")
    public static final int REPORT_FORMAT_VERSION = SharedConstants.REPORT_FORMAT_VERSION;

    private ExtractorVersion() {
    }

    /** Human readable {@code "26.3 (protocol 777, data pack 121.0)"}. */
    public static String describe() {
        return MINECRAFT_VERSION + " (protocol " + PROTOCOL_VERSION + ", data pack " + DATA_PACK_VERSION + ")";
    }
}
