package com.terila.extractor.extractors.protocol;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;

/**
 * Writes {@code protocol/feature_flags.json}: the feature flag vocabulary used by
 * {@code ClientboundUpdateEnabledFeaturesPacket}.
 *
 * <p>The packet carries only identifiers, so this document deliberately does not try to turn flags
 * into data. It records the complete registry and the two sets the game uses by default, which is
 * what a client needs to validate the list the server sends.
 */
public final class FeatureFlagsExtractor extends AbstractExtractor {

    public FeatureFlagsExtractor() {
        super("feature_flags", DataType.PROTOCOL, "protocol/feature_flags.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Set<String> all = names(FeatureFlags.REGISTRY.allFlags());
        Set<String> defaults = names(FeatureFlags.DEFAULT_FLAGS);
        Set<String> vanilla = names(FeatureFlags.VANILLA_SET);

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), all.size()));
        root.addProperty("source", "minecraft:feature-flags");
        root.addProperty("flag_count", all.size());
        root.add("all_flags", array(all));
        root.add("default_flags", array(defaults));
        root.add("vanilla_flags", array(vanilla));
        root.addProperty("default_flag_count", defaults.size());
        root.addProperty("vanilla_flag_count", vanilla.size());
        write(context, root);
        return all.size();
    }

    private static Set<String> names(FeatureFlagSet flags) {
        return FeatureFlags.REGISTRY.toNames(flags).stream()
                .map(Identifier::toString)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    private static JsonArray array(Set<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted(Comparator.naturalOrder()).forEach(array::add);
        return array;
    }
}

