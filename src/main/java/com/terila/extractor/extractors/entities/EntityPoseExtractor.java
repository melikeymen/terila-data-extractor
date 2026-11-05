package com.terila.extractor.extractors.entities;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.world.entity.Pose;

import java.io.IOException;
import java.util.Locale;

/**
 * Writes {@code entities/poses.json}: the {@link Pose} enum, which is what the {@code pose}
 * metadata entry transmits as a varint.
 */
public final class EntityPoseExtractor extends AbstractExtractor {

    public EntityPoseExtractor() {
        super("entity_poses", DataType.ENTITIES, "entities/poses.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        JsonArray data = new JsonArray();
        int index = 0;
        for (Pose pose : Pose.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("index", index++);
            json.addProperty("id", pose.name().toLowerCase(Locale.ROOT));
            data.add(json);
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.add("data", data);
        write(context, root);
        return data.size();
    }
}
