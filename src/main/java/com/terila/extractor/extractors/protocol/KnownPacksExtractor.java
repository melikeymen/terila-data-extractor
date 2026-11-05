package com.terila.extractor.extractors.protocol;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.core.ExtractorVersion;
import net.minecraft.server.packs.repository.KnownPack;

import java.io.IOException;
/**
 * Writes {@code protocol/known_packs.json}: the vanilla pack identity advertised in
 * {@code ClientboundSelectKnownPacksPacket}.
 *
 * <p>A client uses this list to tell the server which data packs it already has, so the server can
 * omit their contents from the configuration transfer. Vanilla ships one known pack,
 * {@code minecraft:core}; the version is taken from the same Minecraft version as the rest of the
 * output rather than being duplicated as a literal here.
 */
public final class KnownPacksExtractor extends AbstractExtractor {

    public KnownPacksExtractor() {
        super("known_packs", DataType.PROTOCOL, "protocol/known_packs.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        KnownPack vanilla = KnownPack.vanilla("core");

        JsonArray packs = new JsonArray();
        JsonObject pack = new JsonObject();
        pack.addProperty("namespace", vanilla.namespace());
        pack.addProperty("id", vanilla.id());
        pack.addProperty("version", vanilla.version());
        pack.addProperty("vanilla", vanilla.isVanilla());
        packs.add(pack);

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), packs.size()));
        root.addProperty("source", "minecraft:known-packs");
        root.addProperty("known_pack_count", packs.size());
        root.addProperty("data_pack_version", ExtractorVersion.DATA_PACK_VERSION);
        root.add("known_packs", packs);
        write(context, root);
        return packs.size();
    }
}

