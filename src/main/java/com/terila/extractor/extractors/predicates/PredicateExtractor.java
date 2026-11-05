package com.terila.extractor.extractors.predicates;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import com.terila.extractor.extractors.datapack.DatapackJsonExtractor;
import com.terila.extractor.extractors.registry.RegistryVocabulary;

/**
 * Writes {@code predicates/minecraft.json}: every predicate, read from
 * {@code data/minecraft/predicate/}.
 *
 * <p>A predicate is what a data pack writes when it needs a boolean condition. The vanilla pack
 * ships a few, and the document also carries the complete type vocabulary — every {@code type} value
 * a predicate file may use, across the six registries that back the format (loot conditions, entity
 * sub-predicates, block predicates, data component predicates and the two rule tests). Without it the
 * file would say which predicates exist and nothing about the format they use.
 */
public final class PredicateExtractor extends DatapackJsonExtractor {

    public PredicateExtractor() {
        super("predicates", DataType.PREDICATES, "predicates/minecraft.json", "predicate");
    }

    @Override
    protected void decorate(ExtractorContext context, JsonObject root) {
        RegistryVocabulary.describe(context, root,
                "minecraft:loot_condition_type",
                "minecraft:entity_sub_predicate_type",
                "minecraft:block_predicate_type",
                "minecraft:data_component_predicate_type",
                "minecraft:pos_rule_test",
                "minecraft:rule_test");
    }
}
