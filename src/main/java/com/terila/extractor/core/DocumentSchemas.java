package com.terila.extractor.core;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON Schemas for the documents the extractor writes.
 *
 * <p>These are the machine readable half of the output contract: {@link SchemaValidator} checks every
 * emitted document against the schema for its path, which is what turns "the extractor ran" into
 * "the extractor produced the shape it promised". The check exists because the failures this project
 * actually hit were silent ones — a codec that emitted only references, a palette key that was
 * renamed, a registry dump with no payload — and every one of them produced valid JSON with the
 * wrong content.
 *
 * <p>Two layers:
 *
 * <ol>
 *   <li><strong>Generic.</strong> Every document that carries a {@code _meta} block must satisfy
 *       {@link #metaCarrier()}: the block is present, has all nine documented fields with the right
 *       types, and contains nothing else. Because every extractor produces its metadata through
 *       {@link ExtractorContext#meta}, a single schema covers ~1500 documents at once.</li>
 *   <li><strong>Per path.</strong> The data sets an engine reads have their own schema, keyed by the
 *       exact output path or by a directory prefix (one per structure template, one per registry
 *       dump, ...). These are the strict ones: they list the fields the document must have and, in
 *       the case of documents whose field set is part of the design, forbid anything else.</li>
 * </ol>
 *
 * <p>Only the JSON Schema subset {@link SchemaValidator} implements is used, so no additional
 * dependency is needed. The schemas are built in code rather than loaded from resources for the same
 * reason {@link ExtractorVersion} reads the game: so a version bump cannot leave a stale copy behind
 * and a field rename becomes a compile error here instead of a runtime surprise.
 */
public final class DocumentSchemas {

    private DocumentSchemas() {
    }

    /**
     * Builds one closed JSON-schema object node.
     *
     * <p>Keeping all schema nodes on this small builder path is what makes the generated schemas
     * comparable: every object is closed by default, and the required list is emitted only when it
     * is non-empty so an optional-fields object stays easy to read.
     */
    private static JsonObject schema(JsonObject properties, boolean allowAdditional, String... required) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "object");
        node.addProperty("additionalProperties", allowAdditional);
        if (required.length > 0) {
            JsonArray list = new JsonArray();
            for (String field : required) {
                list.add(field);
            }
            node.add("required", list);
        }
        if (properties != null) {
            node.add("properties", properties);
        }
        return node;
    }

    /** Pairs field names with schema nodes, in declaration order. */
    private static JsonObject fields(Object... namesAndTypes) {
        JsonObject properties = new JsonObject();
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            properties.add((String) namesAndTypes[i], (JsonObject) namesAndTypes[i + 1]);
        }
        return properties;
    }

    /** A schema node that only constrains the JSON type. */
    private static JsonObject type(String type) {
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        return node;
    }

    /** A string schema node. */
    private static JsonObject string() {
        return type("string");
    }

    /** A boolean schema node. */
    private static JsonObject bool() {
        return type("boolean");
    }

    /** A number schema node (integers also satisfy JSON Schema's number type). */
    private static JsonObject number() {
        return type("number");
    }

    /** An integer with an inclusive lower bound. */
    private static JsonObject integer(int minimum) {
        JsonObject node = type("integer");
        node.addProperty("minimum", minimum);
        return node;
    }

    /**
     * An object with the named members required and unconstrained.
     *
     * <p>The names are also declared as properties, which is what makes
     * {@code allowAdditional = false} mean "exactly these": {@link SchemaValidator} decides whether a
     * member is unexpected by looking it up in {@code properties}, so leaving that map out would make
     * every declared member look unknown.
     */
    private static JsonObject object(boolean allowAdditional, String... required) {
        JsonObject properties = new JsonObject();
        for (String field : required) {
            properties.add(field, new JsonObject());
        }
        return schema(properties, allowAdditional, required);
    }

    /** An object with an explicit property map and required list. */
    private static JsonObject objectOf(JsonObject properties, boolean allowAdditional, String... required) {
        return schema(properties, allowAdditional, required);
    }

    /** An array with no constraint on its elements. */
    private static JsonObject array() {
        return type("array");
    }

    /** An array whose elements all satisfy {@code items}. */
    private static JsonObject arrayOf(JsonObject items) {
        JsonObject node = type("array");
        node.add("items", items);
        return node;
    }

    /**
     * Schema of the {@code _meta} block attached to every extracted document.
     *
     * <p>Closed on purpose ({@code additionalProperties: false}): a typo in a metadata field name
     * would otherwise go unnoticed, and metadata is the only part of the output every consumer reads.
     */
    private static final JsonObject META_BLOCK = schema(fields(
            "minecraft_version", string(),
            "protocol_version", integer(0),
            "data_pack_version", string(),
            "world_version", integer(0),
            "generated_at", string(),
            "source", string(),
            "extractor_name", string(),
            "entry_count", integer(0)
    ), false,
            "minecraft_version", "protocol_version", "data_pack_version",
            "world_version", "generated_at", "source", "extractor_name", "entry_count");

    /**
     * Schema every document carrying metadata must satisfy.
     *
     * <p>Deliberately only about {@code _meta}: documents shaped as {@code {_meta, data}} use
     * {@link #envelope()} instead, and the remaining ones (keyed reports, per-block maps) legitimately
     * have their own roots.
     */
    public static JsonObject metaCarrier() {
        JsonObject properties = fields("_meta", META_BLOCK);
        return schema(properties, true, "_meta");
    }

    /** Schema of the {@code {_meta, data: [...]}} envelope most data sets use. */
    public static JsonObject envelope() {
        JsonObject properties = fields("_meta", META_BLOCK, "data", array());
        return schema(properties, true, "_meta", "data");
    }

    /** Envelope whose {@code data} elements must all satisfy {@code item}. */
    public static JsonObject envelopeOf(JsonObject item) {
        JsonObject properties = fields("_meta", META_BLOCK, "data", arrayOf(item));
        return schema(properties, true, "_meta", "data");
    }

    /**
     * One block catalogue entry.
     *
     * <p>The required list is the contract; extra members are allowed because some fields (an item's
     * {@code max_damage}, a block's derived flags) are only present when they apply. A renamed or
     * dropped field still fails, which is the regression this guards against.
     */
    private static final JsonObject BLOCK_ENTRY = schema(fields(
            "id", string(),
            "protocol_id", integer(-1),
            "name", string(),
            "translation_key", string(),
            "hardness", number(),
            "blast_resistance", number(),
            "friction", number(),
            "jump_factor", number(),
            "speed_factor", number(),
            "light_emission", integer(0),
            "sound_type", string(),
            "map_color", string(),
            "has_collision", bool(),
            "is_solid_render", bool(),
            "can_occlude", bool(),
            "is_light_permeable", bool(),
            "is_air", bool(),
            "is_liquid", bool(),
            "requires_correct_tool", bool(),
            "randomly_ticking", bool(),
            "harvest_tools", array(),
            "state_count", integer(0),
            "states", arrayOf(object(true, "id", "properties")),
            "properties", arrayOf(object(true, "name", "type", "values")),
            "default_state", object(true, "id", "properties"),
            "min_state_id", integer(-1),
            "max_state_id", integer(-1)
    ), true,
            "id", "protocol_id", "name", "translation_key", "hardness", "blast_resistance",
            "friction", "jump_factor",
            "speed_factor", "light_emission", "sound_type", "map_color", "has_collision",
            "is_solid_render", "can_occlude", "is_light_permeable", "is_air", "is_liquid",
            "requires_correct_tool", "randomly_ticking", "harvest_tools", "state_count", "states",
            "properties", "default_state", "min_state_id", "max_state_id");

    /** One item catalogue entry; same contract shape as a block entry. */
    private static final JsonObject ITEM_ENTRY = schema(fields(
            "id", string(),
            "protocol_id", integer(-1),
            "name", string(),
            "translation_key", string(),
            "max_stack_size", integer(-1),
            "is_damageable", bool(),
            "is_food", bool(),
            "is_fire_resistant", bool(),
            "rarity", string(),
            "enchantability", integer(-1),
            "component_count", integer(0),
            "component_ids", array(),
            "components", object(true),
            "components_bound", bool()
    ), true,
            "id", "protocol_id", "name", "translation_key", "max_stack_size", "is_damageable",
            "is_food", "is_fire_resistant", "rarity", "enchantability", "component_count",
            "component_ids", "components", "components_bound");

    private static final Map<String, JsonObject> EXACT = buildExact();
    private static final Map<String, JsonObject> PREFIXES = buildPrefixes();

    /** Paths that may legitimately hold a non-array {@code data} member, so the envelope check skips them. */
    private static final List<String> DATA_MAY_BE_OBJECT = List.of(
            "translations/en_us.json",
            "translations/keys.json",
            "worldgen/biome_parameters.json",
            "items/component_defaults.json"
    );

    /**
     * Path prefixes whose {@code data} member is a map of keys to values rather than an entry array.
     *
     * <p>A language file is a key/value map, not a list, and there is one document per locale, so the
     * exception is expressed as a prefix instead of listing 140 paths.
     */
    private static final List<String> DATA_MAY_BE_OBJECT_PREFIXES = List.of(
            "translations/locales/"
    );

    private static Map<String, JsonObject> buildExact() {
        Map<String, JsonObject> schemas = new LinkedHashMap<>();

        // The run level reports, written by ExtractionRunner rather than an extractor.
        schemas.put("_meta.json", schema(fields(
                "minecraft_version", string(),
                "protocol_version", integer(0),
                "world_version", integer(0),
                "data_pack_version", string(),
                "resource_pack_version", string(),
                "generated_at", string(),
                "duration_ms", integer(0),
                "total_extractors", integer(0),
                "successful_extractors", integer(0),
                "failed_extractors", integer(0),
                "total_entries", integer(0),
                // Extra members are allowed because a failed extractor additionally records "error".
                "extractors", arrayOf(object(true, "name", "type", "output", "success",
                        "entry_count", "duration_ms")),
                "report_hash", string(),
                "changelog", object(true, "generator", "format")
        ), true,
                "minecraft_version", "protocol_version", "world_version",
                "data_pack_version", "resource_pack_version", "generated_at", "duration_ms",
                "total_extractors", "successful_extractors", "failed_extractors", "total_entries",
                "extractors", "report_hash", "changelog"));

        schemas.put("_errors.json", schema(fields(
                "errors", arrayOf(object(true, "severity", "type", "message")),
                "warnings", arrayOf(object(true, "severity", "type", "message")),
                "summary", object(false, "total_entries", "successful", "failed", "warnings")
        ), false, "errors", "warnings", "summary"));

        // Written by the validation pass itself, after that pass has already run, so it is checked on
        // the following run — which is enough for the contract to be enforced.
        schemas.put(ExtractionRunner.VALIDATION_FILE, schema(fields(
                "_meta", META_BLOCK,
                "performed", bool(),
                "summary", object(true, "documents", "validated", "unchecked", "errors", "warnings",
                        "valid"),
                "schemas", object(true, "count"),
                "extractors", arrayOf(object(true, "name", "output", "entries", "success"))
        ), true, "_meta", "performed"));

        // Catalogues.
        schemas.put("blocks/minecraft.json", envelopeOf(BLOCK_ENTRY));
        schemas.put("items/minecraft.json", envelopeOf(ITEM_ENTRY));

        // Block entity type lookup used by chunk decoding.
        schemas.put("blocks/block_entities.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "mapping_count", integer(0),
                "block_entity_type_count", integer(0),
                "data", arrayOf(schema(fields(
                        "block", string(),
                        "block_protocol_id", integer(-1),
                        "block_entity_type", string(),
                        "block_entity_protocol_id", integer(-1)
                ), false, "block", "block_protocol_id", "block_entity_type",
                        "block_entity_protocol_id"))
        ), true, "_meta", "source", "mapping_count", "block_entity_type_count", "data"));

        schemas.put("blocks/state_ids.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "block_count", integer(0),
                "state_count", integer(0),
                "highest_state_id", integer(-1),
                "runtime_id_mismatches", integer(0),
                "data", arrayOf(schema(fields(
                        "id", string(),
                        "properties", object(true),
                        "default_state_id", integer(-1),
                        "min_state_id", integer(-1),
                        "max_state_id", integer(-1),
                        "state_count", integer(0),
                        // A property-less block still has one state, so only the id is guaranteed;
                        // the default state additionally carries "default": true.
                        "states", arrayOf(object(true, "id"))
                ), true, "id", "default_state_id", "min_state_id", "max_state_id", "state_count",
                        "states"))
        ), true, "_meta", "block_count", "state_count", "highest_state_id", "runtime_id_mismatches",
                "data"));

        // Registry surface.
        schemas.put("registries/index.json", schema(fields(
                "_meta", META_BLOCK,
                "index", arrayOf(object(true, "id", "source", "entry_count", "dump")),
                "registry_count", integer(0),
                "entry_count", integer(0),
                "datapack_registry_count", integer(0),
                "builtin_registry_count", integer(0),
                "nbt_registry_count", integer(0)
        ), true, "_meta", "index", "registry_count", "entry_count", "datapack_registry_count",
                "builtin_registry_count"));

        schemas.put("registries/report.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "registry_count", integer(0),
                "entry_count", integer(0),
                "index", array(),
                "registries", object(true)
        ), true, "_meta", "registry_count", "entry_count"));

        schemas.put("registries/datapack_formats.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "registry_count", integer(0),
                "data_pack_version", string(),
                "element_registries", array(),
                "tag_registries", array(),
                "unstable_registries", array(),
                "other_directories", object(true),
                "registries", object(true)
        ), true, "_meta", "registry_count", "element_registries", "other_directories"));

        // Loot tables grouped by block: a keyed report rather than a list of entries.
        schemas.put("loot/blocks.json", schema(fields(
                "_meta", META_BLOCK,
                "by_block", object(true)
        ), true, "_meta", "by_block"));

        // Function sources: the only datapack data set that is not JSON.
        schemas.put("functions/minecraft.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "function_count", integer(0),
                "total_line_count", integer(0),
                "total_command_count", integer(0),
                "total_source_bytes", integer(0),
                "call_count", integer(0),
                "dynamic_call_count", integer(0),
                "tag_call_count", integer(0),
                "tag_expansion_mismatch_count", integer(0),
                "effective_tag_expansion_available", bool(),
                "tag_expansion_differences", arrayOf(schema(fields(
                        "id", string(),
                        "call_count", integer(0),
                        "only_raw_functions", arrayOf(string()),
                        "only_effective_functions", arrayOf(string())
                ), false, "id", "call_count", "only_raw_functions", "only_effective_functions")),
                "missing_reference_count", integer(0),
                "broken_function_count", integer(0),
                "call_cycle_count", integer(0),
                "self_call_count", integer(0),
                "cyclic_function_count", integer(0),
                "cycles", arrayOf(schema(fields(
                        "functions", arrayOf(string()),
                        "self_call", bool()
                ), false, "functions", "self_call")),
                "vanilla_provides", bool(),
                "data", arrayOf(schema(fields(
                        "id", string(),
                        "resource", string(),
                        "source_bytes", integer(0),
                        "line_count", integer(0),
                        "command_count", integer(0),
                        "comment_count", integer(0),
                        "blank_line_count", integer(0),
                        "macro_count", integer(0),
                        "call_count", integer(0),
                        "calls", arrayOf(schema(fields(
                                "target", string(),
                                "type", string(),
                                "line", integer(1),
                                "dynamic", bool(),
                                "exists", bool()
                        ), false, "target", "type", "line", "dynamic", "exists")),
                        "missing_references", arrayOf(string()),
                        "sha256", string(),
                        "content", string()
                ), false, "id", "resource", "source_bytes", "line_count", "command_count",
                        "comment_count", "blank_line_count", "macro_count", "call_count", "calls",
                        "missing_references", "sha256", "content"))
        ), true, "_meta", "source", "function_count", "total_line_count", "total_command_count",
                "total_source_bytes", "call_count", "dynamic_call_count", "tag_call_count",
                "tag_expansion_mismatch_count", "effective_tag_expansion_available",
                "tag_expansion_differences", "missing_reference_count", "broken_function_count",
                "call_cycle_count", "self_call_count", "cyclic_function_count", "cycles",
                "vanilla_provides", "data"));

        // Function tags carry both the declared values and the graph they resolve to.
        schemas.put("tags/functions.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "function_count", integer(0),
                "tag_count", integer(0),
                "reference_count", integer(0),
                "function_reference_count", integer(0),
                "tag_reference_count", integer(0),
                "resolved_function_reference_count", integer(0),
                "missing_reference_count", integer(0),
                "optional_missing_reference_count", integer(0),
                "broken_tag_count", integer(0),
                "cyclic_tag_count", integer(0),
                "all_references_resolve", bool(),
                "effective_view_available", bool(),
                "effective_tag_count", integer(0),
                "effective_function_reference_count", integer(0),
                "mismatched_tag_count", integer(0),
                "matches_effective_view", bool(),
                "effective_differences", arrayOf(schema(fields(
                        "id", string(),
                        "only_declared_functions", arrayOf(string()),
                        "only_effective_functions", arrayOf(string())
                ), false, "id", "only_declared_functions", "only_effective_functions")),
                "vanilla_provides", bool(),
                "data", arrayOf(schema(fields(
                        "id", string(),
                        "values", array(),
                        "replace", bool(),
                        "references", arrayOf(object(false, "id", "type", "required", "exists")),
                        "direct_functions", arrayOf(string()),
                        "nested_tags", arrayOf(string()),
                        "resolved_functions", arrayOf(string()),
                        "resolved_function_count", integer(0),
                        "missing_references", arrayOf(string()),
                        "optional_missing_references", arrayOf(string()),
                        "cyclic", bool(),
                        "effective_functions", arrayOf(string()),
                        "effective_function_count", integer(0),
                        "matches_effective", bool()
                ), false, "id", "values", "replace", "references", "direct_functions",
                        "nested_tags", "resolved_functions", "resolved_function_count",
                        "missing_references", "optional_missing_references", "cyclic"))
        ), true, "_meta", "source", "function_count", "tag_count", "reference_count",
                "function_reference_count", "tag_reference_count",
                "resolved_function_reference_count", "missing_reference_count",
                "optional_missing_reference_count", "broken_tag_count", "cyclic_tag_count",
                "all_references_resolve", "vanilla_provides", "data"));

        // The game's merged, flattened view of the same tags.
        schemas.put("tags/effective_functions.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "function_count", integer(0),
                "tag_count", integer(0),
                "function_reference_count", integer(0),
                "vanilla_provides", bool(),
                "data", arrayOf(schema(fields(
                        "id", string(),
                        "function_count", integer(0),
                        "functions", arrayOf(string())
                ), false, "id", "function_count", "functions"))
        ), true, "_meta", "source", "function_count", "tag_count", "function_reference_count",
                "vanilla_provides", "data"));

        // Structures.
        schemas.put("structures/index.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "template_count", integer(0),
                "total_block_count", integer(0),
                "total_source_bytes", integer(0),
                "failed_templates", integer(0),
                "groups", object(true),
                "templates", arrayOf(object(true, "id", "file", "size_x", "size_y", "size_z",
                        "volume", "block_count", "palette_count", "distinct_block_states"))
        ), true, "_meta", "template_count", "total_block_count", "failed_templates", "templates"));

        // Protocol surface.
        schemas.put("packets/ids.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "states", object(true),
                "packets", arrayOf(object(false, "name", "protocol_id", "state", "direction"))
        ), true, "_meta", "packets", "states", "source"));

        // Packet payload shapes, derived from the packet classes and their stream codecs.
        JsonObject packetState = schema(fields(
                "state", string(),
                "protocol_id", integer(-1)
        ), false, "state", "protocol_id");
        JsonObject packetField = schema(fields(
                "index", integer(0),
                "name", string(),
                "java_type", string(),
                "declared_in", string(),
                "source", string(),
                "codec_component", integer(-1),
                "wire_type", string(),
                "wire_type_source", string(),
                "note", string()
        ), false, "index", "name", "java_type", "declared_in", "source",
                "codec_component", "wire_type", "wire_type_source");
        JsonObject codecComponent = schema(fields(
                "index", integer(0),
                "class", string(),
                "constant", string(),
                "value_type", string(),
                "wire_type", string(),
                "wire_type_source", string()
        ), false, "index", "class", "constant", "value_type", "wire_type", "wire_type_source");
        JsonObject packetCodec = schema(fields(
                "kind", string(),
                "class", string(),
                "component_count", integer(0),
                "components", arrayOf(codecComponent)
        ), false, "kind", "class", "component_count", "components");
        JsonObject packetStructure = schema(fields(
                "name", string(),
                "direction", string(),
                "class", string(),
                "format", string(),
                "type_constant", string(),
                "type_class", string(),
                "serialized_by", string(),
                "states", arrayOf(packetState),
                "protocol_ids", arrayOf(integer(-1)),
                "field_count", integer(0),
                "fields", arrayOf(packetField),
                "codec", packetCodec
        ), false, "name", "direction", "class", "format", "type_constant", "type_class",
                "serialized_by", "states", "protocol_ids", "field_count", "fields", "codec");
        schemas.put("packets/structures.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "packet_count", integer(0),
                "structure_count", integer(0),
                "class_count", integer(0),
                "state_count", integer(0),
                "field_count", integer(0),
                "resolved_count", integer(0),
                "unresolved_count", integer(0),
                "codec_missing_count", integer(0),
                "known_codec_count", integer(0),
                "format_note", string(),
                "unresolved", arrayOf(schema(fields(
                        "name", string(),
                        "state", string(),
                        "direction", string(),
                        "protocol_id", integer(-1)
                ), false, "name", "state", "direction", "protocol_id")),
                "data", arrayOf(packetStructure)
        ), true, "_meta", "source", "packet_count", "structure_count", "class_count",
                "state_count", "field_count", "resolved_count", "unresolved_count",
                "codec_missing_count", "known_codec_count", "format_note", "unresolved", "data"));

        schemas.put("protocol/summary.json", schema(fields(
                "_meta", META_BLOCK,
                "versions", objectOf(fields(
                        "minecraft", string(),
                        "protocol", integer(0),
                        "data_pack_version", string(),
                        "data_pack_version_major", integer(0),
                        "data_pack_version_minor", integer(0),
                        "resource_pack_version", string(),
                        "world_version", integer(0),
                        "report_format_version", integer(0)
                ), false, "minecraft", "protocol", "data_pack_version", "resource_pack_version",
                        "world_version"),
                "packet_count", integer(0),
                "packet_ids_available", bool(),
                "synchronized_registries", array(),
                "synchronized_registry_count", integer(0),
                "reports_available", bool()
        ), true, "_meta", "versions", "packet_count", "packet_ids_available",
                "synchronized_registries", "reports_available"));

        schemas.put("protocol/feature_flags.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "flag_count", integer(0),
                "all_flags", arrayOf(string()),
                "default_flags", arrayOf(string()),
                "vanilla_flags", arrayOf(string()),
                "default_flag_count", integer(0),
                "vanilla_flag_count", integer(0)
        ), true, "_meta", "source", "flag_count", "all_flags", "default_flags", "vanilla_flags"));

        schemas.put("protocol/known_packs.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "known_pack_count", integer(0),
                "data_pack_version", string(),
                "known_packs", arrayOf(schema(fields(
                        "namespace", string(),
                        "id", string(),
                        "version", string(),
                        "vanilla", bool()
                ), false, "namespace", "id", "version", "vanilla"))
        ), true, "_meta", "source", "known_pack_count", "data_pack_version", "known_packs"));

        schemas.put("protocol/json_rpc.json", schema(fields(
                "_meta", META_BLOCK,
                "incoming_method_count", integer(0),
                "outgoing_method_count", integer(0),
                "schema", object(true)
        ), true, "_meta", "incoming_method_count", "outgoing_method_count", "schema"));

        // Commands.
        schemas.put("commands/tree.json", schema(fields(
                "_meta", META_BLOCK,
                "root_command_count", integer(0),
                "tree", object(true)
        ), true, "_meta", "root_command_count", "tree"));

        schemas.put("commands/report.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "node_count", integer(0),
                "executable_count", integer(0),
                "argument_count", integer(0),
                "root_command_count", integer(0),
                "tree", object(true)
        ), true, "_meta", "node_count", "tree"));

        // Items driven by the data generator report.
        schemas.put("items/component_defaults.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "item_count", integer(0),
                "component_assignments", integer(0),
                "distinct_components", integer(0),
                "component_usage", object(true),
                "data", object(true)
        ), true, "_meta", "item_count", "component_assignments", "component_usage", "data"));

        // Client assets.
        schemas.put("assets/index.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "asset_count", integer(0),
                "json_assets_mirrored", integer(0),
                "binary_assets_listed", integer(0),
                "total_bytes", integer(0),
                "groups", object(true),
                "binary_asset_types", object(true),
                "assets", arrayOf(object(true, "path", "bytes", "crc32", "kind"))
        ), true, "_meta", "asset_count", "json_assets_mirrored", "binary_assets_listed",
                "assets", "groups"));

        schemas.put("assets/manifest.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "binary_asset_count", integer(0),
                "distinct_content_hashes", integer(0),
                "copied_binary_bytes", integer(0),
                "content_hashes", array(),
                "binary_asset_types", object(true)
        ), true, "_meta", "binary_asset_count", "content_hashes"));

        // Translations: the only documents whose data member is a key/value object.
        schemas.put("translations/en_us.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "data", object(true)
        ), true, "_meta", "data"));

        schemas.put("translations/keys.json", schema(fields(
                "_meta", META_BLOCK,
                "data", object(true),
                "missing", object(true)
        ), true, "_meta", "data", "missing"));

        schemas.put("translations/locales.json", schema(fields(
                "_meta", META_BLOCK,
                "locales", object(true)
        ), true, "_meta", "locales"));

        // The tag sweep: every bound tag of every registry, grouped by registry.
        schemas.put("tags/all.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "registry_count", integer(0),
                "tag_count", integer(0),
                "value_count", integer(0),
                "data", arrayOf(schema(fields(
                        "registry", string(),
                        "tag_count", integer(0),
                        "value_count", integer(0),
                        "tags", arrayOf(schema(fields(
                                "id", string(),
                                "values", array(),
                                "replace", bool(),
                                "value_count", integer(0)
                        ), false, "id", "values", "replace", "value_count"))
                ), true, "registry", "tag_count", "value_count", "tags"))
        ), true, "_meta", "source", "registry_count", "tag_count", "value_count", "data"));

        // The inverse of the tag sweep: one entry per tagged id, not one per tag.
        schemas.put("tags/by_entry.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "registry_count", integer(0),
                "entry_count", integer(0),
                "membership_count", integer(0),
                "data", arrayOf(schema(fields(
                        "registry", string(),
                        "entry_count", integer(0),
                        "membership_count", integer(0),
                        "entries", arrayOf(schema(fields(
                                "id", string(),
                                "tag_count", integer(0),
                                "tags", arrayOf(string())
                        ), false, "id", "tag_count", "tags"))
                ), true, "registry", "entry_count", "membership_count", "entries"))
        ), true, "_meta", "source", "registry_count", "entry_count", "membership_count", "data"));

        // Gameplay catalogs whose built-in registry entries are otherwise identity-only.
        schemas.put("gameplay/game_rules.json", schema(fields(
                "_meta", META_BLOCK,
                "rule_count", integer(0),
                "data", arrayOf(schema(fields(
                        "id", string(),
                        "description_id", string(),
                        "category", string(),
                        "type", string(),
                        "value_class", string(),
                        // Boolean and integer defaults share one document, so no narrower type.
                        "default_value", new JsonObject(),
                        "default_value_text", string()
                ), false, "id", "description_id", "category", "type", "value_class",
                        "default_value", "default_value_text"))
        ), true, "_meta", "rule_count", "data"));

        schemas.put("gameplay/potions.json", schema(fields(
                "_meta", META_BLOCK,
                "potion_count", integer(0),
                "total_effect_count", integer(0),
                "data", arrayOf(schema(fields(
                        "id", string(),
                        "name", string(),
                        "instant", bool(),
                        "effect_count", integer(0),
                        "effects", arrayOf(schema(fields(
                                "effect", string(),
                                "description_id", string(),
                                "duration_ticks", integer(0),
                                "amplifier", integer(0),
                                "ambient", bool(),
                                "visible", bool(),
                                "show_icon", bool()
                        ), false, "effect", "description_id", "duration_ticks", "amplifier",
                                "ambient", "visible", "show_icon"))
                ), false, "id", "name", "instant", "effect_count", "effects"))
        ), true, "_meta", "potion_count", "total_effect_count", "data"));

        // Worldgen report with an object payload.
        schemas.put("worldgen/biome_parameters.json", schema(fields(
                "_meta", META_BLOCK,
                "source", string(),
                "list_count", integer(0),
                "parameter_count", integer(0),
                "data", object(true)
        ), true, "_meta", "list_count", "parameter_count", "data"));

        return Map.copyOf(schemas);
    }

    private static Map<String, JsonObject> buildPrefixes() {
        Map<String, JsonObject> schemas = new LinkedHashMap<>();

        // One document per block, describing that block's state table.
        schemas.put("blocks/blockstates/", schema(fields(
                "_meta", META_BLOCK,
                "block_id", string(),
                "default_state_id", integer(-1),
                "min_state_id", integer(-1),
                "max_state_id", integer(-1),
                "states", arrayOf(object(true, "id", "properties"))
        ), true, "_meta", "block_id", "states"));

        // One document per registry, carrying that registry's entries.
        schemas.put("registries/dump/", schema(fields(
                "_meta", META_BLOCK,
                "registry", string(),
                "source", string(),
                "registries", schema(fields(
                        "entry_count", integer(0),
                        "min_protocol_id", integer(0),
                        "max_protocol_id", integer(0),
                        "entries_without_codec_data", integer(0),
                        // Only the identifier is guaranteed: a built-in registry is dumped by identity,
                        // and a data pack entry only carries a payload when its codec accepted it.
                        "entries", arrayOf(object(true, "id"))
                ), true, "entry_count", "entries")
        ), true, "_meta", "registry", "source", "registries"));

        // One document per locale, next to the en_us.json compatibility document.
        schemas.put("translations/locales/", schema(fields(
                "_meta", META_BLOCK,
                "locale", string(),
                "source", string(),
                "data", object(true)
        ), true, "_meta", "locale", "data"));

        // One document per structure template.
        schemas.put("structures/templates/", schema(fields(
                "id", string(),
                "pack", string(),
                "resource", string(),
                "source_bytes", integer(0),
                "size", object(false, "x", "y", "z"),
                "volume", integer(0),
                "palette_count", integer(0),
                "palette_size", integer(0),
                "palette", array(),
                "blocks", object(true),
                "palettes", array(),
                "block_count", integer(0),
                "distinct_block_states", integer(0),
                "jigsaw_count", integer(0),
                "block_entity_count", integer(0),
                "block_entities", array(),
                "entity_count", integer(0),
                "entities", array()
        ), true,
                "id", "size", "volume", "palette_count", "palette", "palette_size", "blocks",
                "block_count", "distinct_block_states", "block_entity_count", "block_entities",
                "entity_count", "entities"));

        // One document per datapack content directory; all of them are envelopes, and the paths that
        // are not (a keyed report next to them) have their own exact schema.
        schemas.put("advancements/", envelope());
        schemas.put("loot/minecraft.json", envelope());
        schemas.put("predicates/", envelope());
        schemas.put("item_modifiers/", envelope());

        // Directories where every document is a metadata envelope around a data array.
        for (String directory : List.of("worldgen/", "tags/", "recipes/", "enchantments/",
                "damage_types/", "trims/", "misc/", "entities/")) {
            schemas.put(directory, envelope());
        }

        return Map.copyOf(schemas);
    }

    /**
     * The schema for one output document.
     *
     * @param relativePath path relative to the output root, using {@code /} separators.
     * @return the exact schema, the prefix schema, or empty when only the generic metadata contract
     *         applies (which the caller can still check with {@link #metaCarrier()}).
     */
    public static Optional<JsonObject> forPath(String relativePath) {
        JsonObject exact = EXACT.get(relativePath);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Map.Entry<String, JsonObject> entry : PREFIXES.entrySet()) {
            if (relativePath.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** Whether a document is exempt from the "an envelope's {@code data} is an array" rule. */
    public static boolean dataMayBeObject(String relativePath) {
        return DATA_MAY_BE_OBJECT.contains(relativePath)
                || DATA_MAY_BE_OBJECT_PREFIXES.stream().anyMatch(relativePath::startsWith);
    }

    /**
     * Whether a directory holds raw Minecraft assets rather than extractor documents.
     *
     * <p>Everything the client jar ships under {@code assets/} is mirrored verbatim, so it follows the
     * game's own format, not this extractor's; the two files this extractor owns there are validated
     * individually.
     */
    public static boolean isMirroredAsset(String relativePath) {
        return relativePath.startsWith("assets/")
                && !relativePath.equals("assets/index.json")
                && !relativePath.equals("assets/manifest.json");
    }

    /** Paths with an exact schema; exposed so a test can assert the table is not empty. */
    public static List<String> exactPaths() {
        return List.copyOf(EXACT.keySet());
    }

    /** Directory prefixes with a schema; exposed for the same reason as {@link #exactPaths()}. */
    public static List<String> prefixes() {
        return List.copyOf(PREFIXES.keySet());
    }
}

