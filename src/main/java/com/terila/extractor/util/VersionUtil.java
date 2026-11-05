package com.terila.extractor.util;

// Written by AI
import com.terila.extractor.core.ExtractorVersion;
import net.minecraft.SharedConstants;

/** Reads the running game version, matching the design's {@code VersionUtil}. */
public final class VersionUtil {

    private VersionUtil() {
    }

    /** Human readable Minecraft version of the running game, e.g. {@code 26.3}. */
    public static String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    /** Protocol version the running game speaks. */
    public static int protocolVersion() {
        return SharedConstants.getCurrentVersion().protocolVersion();
    }

    /** Data (world) version used to interpret saved and packed data. */
    public static int worldVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /** Major component of the data pack format version. */
    public static int dataPackVersionMajor() {
        return ExtractorVersion.DATA_PACK_VERSION_MAJOR;
    }

    /** Minor component of the data pack format version. */
    public static int dataPackVersionMinor() {
        return ExtractorVersion.DATA_PACK_VERSION_MINOR;
    }

    /** {@code true} when the running game matches the version the schemas were written for. */
    public static boolean isSupported() {
        return ExtractorVersion.MINECRAFT_VERSION.equals(minecraftVersion())
                && protocolVersion() == ExtractorVersion.PROTOCOL_VERSION;
    }

    /** One-line version summary used by the CLI and logs. */
    public static String describe() {
        return ExtractorVersion.describe();
    }
}
