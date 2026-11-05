package com.terila.extractor.extractors.packets;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketType;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes {@code packets/structures.json}: the payload shape of every packet in the protocol table.
 *
 * <p>{@link PacketIdExtractor} answers <em>which</em> packet arrives; this extractor answers
 * <em>what the packet contains</em>. For every state, direction and packet id it records the
 * implementing class, the connection state and direction that accepts it, its record components or
 * declared instance fields and the components captured by the class's {@code STREAM_CODEC}.
 *
 * <p>The packet classes are the authoritative source: their record components and wire codecs are
 * what the game itself uses. A field is reported with a Java type and, when the codec component is one
 * of the well-known {@link ByteBufCodecs} constants, an exact wire type such as {@code varint} or
 * {@code string}. A custom codec has no public introspection API, so its field is marked
 * {@code codec_defined} rather than guessed. The root note says the same thing for consumers.
 */
public final class PacketStructureExtractor extends AbstractExtractor {

    /** Classes that declare the packet-type constants, grouped by protocol state. */
    private static final List<String> TYPE_CLASSES = List.of(
            "net.minecraft.network.protocol.game.GamePacketTypes",
            "net.minecraft.network.protocol.common.CommonPacketTypes",
            "net.minecraft.network.protocol.configuration.ConfigurationPacketTypes",
            "net.minecraft.network.protocol.login.LoginPacketTypes",
            "net.minecraft.network.protocol.handshake.HandshakePacketTypes",
            "net.minecraft.network.protocol.status.StatusPacketTypes",
            "net.minecraft.network.protocol.cookie.CookiePacketTypes",
            "net.minecraft.network.protocol.ping.PingPacketTypes");

    private static final Map<StreamCodec<?, ?>, String> CODEC_CONSTANTS = codecConstants();

    private static final String FORMAT_NOTE =
            "Record components and declared instance fields describe the Java payload. wire_type is "
                    + "authoritative only when wire_type_source is codec_constant; otherwise the listed "
                    + "custom codec is the source of truth for the byte format.";

    public PacketStructureExtractor() {
        super("packet_structures", DataType.PACKETS, "packets/structures.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        Path report = locate(context);
        if (report == null) {
            context.errors().warning("PACKET_REPORT_UNAVAILABLE", name(), null,
                    "No reports/packets.json found; packet structures were skipped "
                            + "(pass --reports <data-generator output dir>)");
            return 0;
        }

        JsonElement parsed = JsonParser.parseString(Files.readString(report, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            context.errors().error("PACKET_REPORT_INVALID", name(), report.toString(), null,
                    "Expected a JSON object");
            return 0;
        }

        TypeIndex types = TypeIndex.load();
        Map<String, Structure> structures = new TreeMap<>();
        JsonArray unresolved = new JsonArray();
        int occurrences = 0;
        int resolved = 0;

        for (var stateEntry : new TreeMap<>(parsed.getAsJsonObject().asMap()).entrySet()) {
            String state = stateEntry.getKey();
            if (!stateEntry.getValue().isJsonObject()) {
                continue;
            }
            for (var directionEntry : new TreeMap<>(stateEntry.getValue().getAsJsonObject().asMap())
                    .entrySet()) {
                String direction = directionEntry.getKey().toLowerCase(Locale.ROOT);
                if (!directionEntry.getValue().isJsonObject()) {
                    continue;
                }
                for (var packetEntry : new TreeMap<>(directionEntry.getValue().getAsJsonObject().asMap())
                        .entrySet()) {
                    occurrences++;
                    String packetName = packetEntry.getKey();
                    int protocolId = protocolId(packetEntry.getValue(), context, state, direction,
                            packetName);
                    PacketTypeInfo type = types.find(packetName, direction);
                    if (type == null) {
                        JsonObject missing = new JsonObject();
                        missing.addProperty("name", packetName);
                        missing.addProperty("state", state);
                        missing.addProperty("direction", direction);
                        missing.addProperty("protocol_id", protocolId);
                        unresolved.add(missing);
                        context.errors().warning("PACKET_STRUCTURE_UNRESOLVED", name(),
                                state + "/" + direction + "/" + packetName,
                                "No PacketType constant and packet class matched this report entry");
                        continue;
                    }

                    resolved++;
                    Field codecField = codecField(type.packetClass(), state);
                    String key = direction + "\u0000" + packetName + "\u0000"
                            + type.packetClass().getName() + "\u0000"
                            + (codecField == null ? "none" : codecField.getName());
                    Structure structure = structures.get(key);
                    if (structure == null) {
                        structure = new Structure(packetName, direction, type, codecField, protocolId,
                                state);
                        structures.put(key, structure);
                    } else {
                        structure.addState(state, protocolId);
                    }
                }
            }
        }

        JsonArray data = new JsonArray();
        int fieldCount = 0;
        int codecMissing = 0;
        Set<String> classes = new LinkedHashSet<>();
        for (Structure structure : structures.values()) {
            JsonObject json = structure.describe();
            data.add(json);
            fieldCount += json.getAsJsonArray("fields").size();
            classes.add(structure.type.packetClass().getName());
            if ("missing".equals(json.getAsJsonObject("codec").get("kind").getAsString())) {
                codecMissing++;
            }
        }

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), data.size()));
        root.addProperty("source", report.toString());
        root.addProperty("packet_count", occurrences);
        root.addProperty("structure_count", data.size());
        root.addProperty("class_count", classes.size());
        root.addProperty("state_count", stateCount(structures.values()));
        root.addProperty("field_count", fieldCount);
        root.addProperty("resolved_count", resolved);
        root.addProperty("unresolved_count", unresolved.size());
        root.addProperty("codec_missing_count", codecMissing);
        root.addProperty("known_codec_count", CODEC_CONSTANTS.size());
        root.addProperty("format_note", FORMAT_NOTE);
        root.add("unresolved", unresolved);
        root.add("data", data);
        write(context, root);
        return data.size();
    }

    private static int protocolId(JsonElement value, ExtractorContext context, String state,
                                  String direction, String packetName) {
        if (value.isJsonObject() && value.getAsJsonObject().has("protocol_id")) {
            JsonElement id = value.getAsJsonObject().get("protocol_id");
            if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()) {
                return id.getAsInt();
            }
        }
        context.errors().warning("PACKET_PROTOCOL_ID_MISSING", "packet_structures",
                state + "/" + direction + "/" + packetName,
                "Report entry has no numeric protocol_id");
        return -1;
    }

    private static int stateCount(Iterable<Structure> structures) {
        Set<String> states = new LinkedHashSet<>();
        for (Structure structure : structures) {
            for (State state : structure.states) {
                states.add(state.name());
            }
        }
        return states.size();
    }

    /** Locates {@code packets.json}, either explicitly configured or under the reports root. */
    private static Path locate(ExtractorContext context) {
        String property = System.getProperty("terila.reports.packets");
        if (property != null) {
            Path explicit = Path.of(property);
            if (Files.isRegularFile(explicit)) {
                return explicit;
            }
        }

        Path reportsRoot = context.reportsRoot().orElse(null);
        if (reportsRoot == null) {
            return null;
        }
        for (Path candidate : List.of(reportsRoot.resolve("packets.json"),
                reportsRoot.resolve("reports").resolve("packets.json"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** One report occurrence's structure, grouped by packet name and direction. */
    private static final class Structure {
        private final String name;
        private final String direction;
        private final PacketTypeInfo type;
        private final Field codecField;
        private final List<State> states = new ArrayList<>();
        private final Set<Integer> protocolIds = new LinkedHashSet<>();

        private Structure(String name, String direction, PacketTypeInfo type, Field codecField,
                          int protocolId, String state) {
            this.name = name;
            this.direction = direction;
            this.type = type;
            this.codecField = codecField;
            addState(state, protocolId);
        }

        private void addState(String state, int protocolId) {
            states.add(new State(state, protocolId));
            states.sort(Comparator.comparing(State::name));
            protocolIds.add(protocolId);
        }

        private JsonObject describe() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("direction", direction);
            json.addProperty("class", type.packetClass().getName());
            json.addProperty("format", type.packetClass().isRecord() ? "record" : "class");
            json.addProperty("type_constant", type.constant());
            json.addProperty("type_class", type.declaringClass());
            json.addProperty("serialized_by", codecField == null ? "unknown" : codecField.getName());

            JsonArray stateArray = new JsonArray();
            for (State state : states) {
                JsonObject entry = new JsonObject();
                entry.addProperty("state", state.name());
                entry.addProperty("protocol_id", state.protocolId());
                stateArray.add(entry);
            }
            json.add("states", stateArray);

            JsonArray ids = new JsonArray();
            protocolIds.stream().sorted().forEach(ids::add);
            json.add("protocol_ids", ids);

            List<FieldInfo> fields = fields(type.packetClass());
            CodecInfo codec = codec(type.packetClass(), codecField);
            // Composite codecs are not required to expose one component per declared field. Only
            // pair by index when the counts agree; otherwise emit the Java type as a hint instead of
            // claiming a wire type for the wrong field.
            boolean aligned = codec.components.size() == fields.size();

            JsonArray fieldArray = new JsonArray();
            for (int i = 0; i < fields.size(); i++) {
                FieldInfo field = fields.get(i);
                JsonObject entry = field.toJson();
                int componentIndex = aligned ? i : -1;
                entry.addProperty("codec_component", componentIndex);
                if (componentIndex >= 0) {
                    CodecComponent component = codec.components.get(componentIndex);
                    entry.addProperty("wire_type", component.wireType());
                    entry.addProperty("wire_type_source", component.wireTypeSource());
                } else {
                    entry.addProperty("wire_type", hint(field.type()));
                    entry.addProperty("wire_type_source", "java_type");
                }
                String packetWireType = packetWireType(name, field.name());
                if (packetWireType != null) {
                    entry.addProperty("wire_type", packetWireType);
                    entry.addProperty("wire_type_source", "packet_spec");
                }
                String note = packetFieldNote(name, field.name());
                if (note != null) {
                    entry.addProperty("note", note);
                }
                fieldArray.add(entry);
            }
            json.addProperty("field_count", fieldArray.size());
            json.add("fields", fieldArray);
            json.add("codec", codec.toJson());
            return json;
        }
    }

    /** A packet class and the packet-type constant that maps to it. */
    private record PacketTypeInfo(String constant, String declaringClass, Class<?> packetClass) {
    }

    /** A `(packet id, direction)` lookup, built from every packet-type class. */
    private static final class TypeIndex {
        private final Map<String, PacketTypeInfo> byKey;

        private TypeIndex(Map<String, PacketTypeInfo> byKey) {
            this.byKey = byKey;
        }

        private static TypeIndex load() {
            Map<String, PacketTypeInfo> result = new LinkedHashMap<>();
            for (String className : TYPE_CLASSES) {
                Class<?> holder;
                try {
                    holder = Class.forName(className);
                } catch (ClassNotFoundException missing) {
                    // A future version may remove or rename one holder; the remaining states still
                    // produce a useful index.
                    continue;
                }
                for (Field field : holder.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())
                            || !PacketType.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    try {
                        PacketType<?> packetType = (PacketType<?>) field.get(null);
                        Class<?> packetClass = packetClass(field);
                        if (packetClass == null) {
                            continue;
                        }
                        String direction = packetType.flow().name().toLowerCase(Locale.ROOT);
                        // NUL cannot occur in a resource id or direction, so it is a safe composite
                        // key separator without allocating a record for every lookup.
                        String key = packetType.id().toString() + "\u0000" + direction;
                        result.putIfAbsent(key, new PacketTypeInfo(field.getName(),
                                holder.getName(), packetClass));
                    } catch (IllegalAccessException failure) {
                        throw new IllegalStateException("Cannot read packet type " + className
                                + "." + field.getName(), failure);
                    }
                }
            }
            return new TypeIndex(result);
        }

        private PacketTypeInfo find(String name, String direction) {
            return byKey.get(name + "\u0000" + direction);
        }

        private static Class<?> packetClass(Field field) {
            Type generic = field.getGenericType();
            if (!(generic instanceof ParameterizedType parameterized)
                    || parameterized.getActualTypeArguments().length != 1) {
                return null;
            }
            Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof Class<?> clazz) {
                return clazz;
            }
            if (argument instanceof ParameterizedType nested
                    && nested.getRawType() instanceof Class<?> clazz) {
                return clazz;
            }
            return null;
        }
    }

    /** One serialisable (or declared) packet field. */
    private record FieldInfo(int index, String name, Type type, String declaredIn, String source) {

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("index", index);
            json.addProperty("name", name);
            json.addProperty("java_type", typeName(type));
            json.addProperty("declared_in", declaredIn);
            json.addProperty("source", source);
            return json;
        }
    }

    /** One component codec captured by a composite stream codec. */
    private record CodecComponent(int index, String codecClass, String constant, String valueType,
                                  String wireType, String wireTypeSource) {

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("index", index);
            json.addProperty("class", codecClass);
            json.addProperty("constant", constant);
            json.addProperty("value_type", valueType);
            json.addProperty("wire_type", wireType);
            json.addProperty("wire_type_source", wireTypeSource);
            return json;
        }
    }

    /** The top-level codec description and its composite components, when exposed. */
    private record CodecInfo(String kind, String codecClass, int componentCount,
                             List<CodecComponent> components) {

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind);
            json.addProperty("class", codecClass);
            json.addProperty("component_count", componentCount);
            JsonArray array = new JsonArray();
            for (CodecComponent component : components) {
                array.add(component.toJson());
            }
            json.add("components", array);
            return json;
        }
    }

    private static List<FieldInfo> fields(Class<?> packetClass) {
        List<FieldInfo> result = new ArrayList<>();
        if (packetClass.isRecord()) {
            int index = 0;
            for (RecordComponent component : packetClass.getRecordComponents()) {
                result.add(new FieldInfo(index++, component.getName(), component.getGenericType(),
                        packetClass.getName(), "record_component"));
            }
            return result;
        }

        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = packetClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        int index = 0;
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                result.add(new FieldInfo(index++, field.getName(), field.getGenericType(),
                        current.getName(), "class_field"));
            }
        }
        return result;
    }

    /**
     * Finds the codec a packet class uses for one connection state.
     *
     * <p>Most packets have one {@code STREAM_CODEC}. A common packet may instead have one codec per
     * state, which is why {@code custom_payload} has {@code CONFIG_STREAM_CODEC} and
     * {@code GAMEPLAY_STREAM_CODEC}; the state-specific field is selected before the generic name.
     */
    private static Field codecField(Class<?> packetClass, String state) {
        Field shared = findCodecField(packetClass, "STREAM_CODEC");
        if (shared != null) {
            return shared;
        }
        if ("configuration".equals(state)) {
            Field config = findCodecField(packetClass, "CONFIG_STREAM_CODEC");
            if (config != null) {
                return config;
            }
        }
        if ("play".equals(state)) {
            Field gameplay = findCodecField(packetClass, "GAMEPLAY_STREAM_CODEC");
            if (gameplay != null) {
                return gameplay;
            }
        }
        return null;
    }

    private static Field findCodecField(Class<?> packetClass, String name) {
        for (Class<?> current = packetClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers())
                        && StreamCodec.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // Keep walking up: a nested packet can inherit its codec from a base class.
            }
        }
        return null;
    }

    private static CodecInfo codec(Class<?> packetClass, Field codecField) {
        if (codecField == null) {
            return new CodecInfo("missing", "", 0, List.of());
        }
        Object codec;
        try {
            codec = codecField.get(null);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read STREAM_CODEC of " + packetClass.getName(),
                    failure);
        }
        if (!(codec instanceof StreamCodec<?, ?>)) {
            return new CodecInfo("missing", "", 0, List.of());
        }

        List<CodecComponent> components = new ArrayList<>();
        for (Field field : codec.getClass().getDeclaredFields()) {
            // A composite codec captures its component codecs as synthetic val$codecN fields. They
            // are an implementation detail of the JDK lambda, so the read is guarded and degrades to
            // "custom" when a future runtime changes the naming scheme.
            if (Modifier.isStatic(field.getModifiers())
                    || !field.getName().startsWith("val$codec")
                    || !StreamCodec.class.isAssignableFrom(field.getType())) {
                continue;
            }
            String suffix = field.getName().substring("val$codec".length());
            if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) {
                continue;
            }
            int index = Integer.parseInt(suffix) - 1;
            try {
                field.setAccessible(true);
                Object value = field.get(codec);
                if (value instanceof StreamCodec<?, ?> component) {
                    String constant = CODEC_CONSTANTS.getOrDefault(component, "");
                    components.add(new CodecComponent(index,
                            stableClassName(component.getClass()), constant,
                            constant.isEmpty() ? "" : codecValueType(constant),
                            constant.isEmpty() ? "codec_defined" : wireType(constant),
                            constant.isEmpty() ? "codec" : "codec_constant"));
                }
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot read " + field.getName() + " of "
                        + codec.getClass().getName(), failure);
            }
        }
        components.sort(Comparator.comparingInt(CodecComponent::index));

        String kind = components.isEmpty() ? "custom" : "composite";
        return new CodecInfo(kind, stableClassName(codec.getClass()), components.size(), components);
    }

    private static Map<StreamCodec<?, ?>, String> codecConstants() {
        Map<StreamCodec<?, ?>, String> constants = new IdentityHashMap<>();
        for (Field field : ByteBufCodecs.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())
                    || !StreamCodec.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof StreamCodec<?, ?> codec) {
                    constants.put(codec, field.getName());
                }
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot read ByteBufCodecs." + field.getName(),
                        failure);
            }
        }
        return constants;
    }

    private static String codecValueType(String constant) {
        try {
            Field field = ByteBufCodecs.class.getDeclaredField(constant);
            Type type = field.getGenericType();
            if (type instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments().length == 2) {
                return typeName(parameterized.getActualTypeArguments()[1]);
            }
        } catch (NoSuchFieldException ignored) {
            // The constant came from the identity map, so this cannot normally happen.
        }
        return "";
    }

    private static String wireType(String constant) {
        return switch (constant) {
            case "BOOL" -> "bool";
            case "BYTE" -> "byte";
            case "ROTATION_BYTE" -> "rotation_byte";
            case "SHORT" -> "short";
            case "UNSIGNED_SHORT" -> "unsigned_short";
            case "INT" -> "int";
            case "VAR_INT" -> "varint";
            case "OPTIONAL_VAR_INT" -> "optional_varint";
            case "LONG" -> "long";
            case "VAR_LONG" -> "var_long";
            case "FLOAT" -> "float";
            case "DOUBLE" -> "double";
            case "BYTE_ARRAY" -> "byte_array";
            case "LONG_ARRAY" -> "long_array";
            case "BIT_SET" -> "bit_set";
            case "STRING_UTF8" -> "string";
            case "TAG", "TRUSTED_TAG" -> "nbt";
            case "COMPOUND_TAG", "TRUSTED_COMPOUND_TAG" -> "compound_nbt";
            case "OPTIONAL_COMPOUND_TAG" -> "optional_compound_nbt";
            case "VECTOR3F" -> "vector3f";
            case "QUATERNIONF" -> "quaternionf";
            case "CONTAINER_ID" -> "container_id";
            case "GAME_PROFILE_PROPERTIES" -> "game_profile_properties";
            case "PLAYER_NAME" -> "player_name";
            case "GAME_PROFILE" -> "game_profile";
            case "RGB_COLOR" -> "rgb_color";
            case "INSTANT" -> "instant";
            case "PUBLIC_KEY" -> "public_key";
            default -> "codec_defined";
        };
    }

    /** Best-effort wire type from a Java field type, used when no codec component is available. */
    private static String hint(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz == boolean.class || clazz == Boolean.class) {
                return "bool";
            }
            if (clazz == byte.class || clazz == Byte.class) {
                return "byte";
            }
            if (clazz == short.class || clazz == Short.class) {
                return "short";
            }
            if (clazz == int.class || clazz == Integer.class) {
                return "int";
            }
            if (clazz == long.class || clazz == Long.class) {
                return "long";
            }
            if (clazz == float.class || clazz == Float.class) {
                return "float";
            }
            if (clazz == double.class || clazz == Double.class) {
                return "double";
            }
            if (clazz == String.class) {
                return "string";
            }
            if (clazz == java.util.UUID.class) {
                return "uuid";
            }
            if (clazz.isArray() && clazz.getComponentType() == byte.class) {
                return "byte_array";
            }
            if (net.minecraft.nbt.Tag.class.isAssignableFrom(clazz)) {
                return "nbt";
            }
            if (clazz.isEnum()) {
                return "enum";
            }
            return "custom";
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw == List.class || raw == Set.class || raw == java.util.Collection.class
                    || raw == java.util.EnumSet.class) {
                return "varint_prefixed_list";
            }
            if (raw == java.util.Optional.class) {
                return "optional";
            }
            if (raw == Map.class) {
                return "varint_prefixed_map";
            }
        }
        return "custom";
    }

    /** Hand-written wire types for stream codecs whose layout is not a ByteBufCodecs constant. */
    private static String packetWireType(String packet, String field) {
        if (packet.equals("minecraft:registry_data")) {
            return switch (field) {
                case "registry" -> "string";
                case "entries" -> "varint_prefixed_list";
                default -> null;
            };
        }
        if (packet.equals("minecraft:update_tags") && field.equals("tags")) {
            return "varint_prefixed_map";
        }
        return null;
    }

    /** Explains the non-obvious registry and update-tags payload layouts. */
    private static String packetFieldNote(String packet, String field) {
        if (packet.equals("minecraft:registry_data")) {
            return switch (field) {
                case "registry" -> "VarInt uzunluk + UTF-8 registry id";
                case "entries" -> "her entry: id String + has_nbt bool + opsiyonel NBT";
                default -> null;
            };
        }
        if (packet.equals("minecraft:update_tags") && field.equals("tags")) {
            return "registry id -> (tag name -> artan protocol id listesi)";
        }
        return null;
    }

    private static String stableClassName(Class<?> type) {
        String name = type.getName();
        if (name.contains("$$Lambda") || name.contains("/0x")) {
            return "lambda";
        }
        return name;
    }

    private static String typeName(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                return typeName(clazz.getComponentType()) + "[]";
            }
            return clazz.getName();
        }
        if (type instanceof ParameterizedType parameterized) {
            StringBuilder result = new StringBuilder(typeName(parameterized.getRawType()));
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length > 0) {
                result.append('<');
                for (int i = 0; i < arguments.length; i++) {
                    if (i > 0) {
                        result.append(", ");
                    }
                    result.append(typeName(arguments[i]));
                }
                result.append('>');
            }
            return result.toString();
        }
        if (type instanceof TypeVariable<?> variable) {
            return variable.getName();
        }
        if (type instanceof WildcardType wildcard) {
            StringBuilder result = new StringBuilder("?");
            if (wildcard.getLowerBounds().length > 0) {
                result.append(" super ").append(typeName(wildcard.getLowerBounds()[0]));
            } else if (wildcard.getUpperBounds().length > 0
                    && wildcard.getUpperBounds()[0] != Object.class) {
                result.append(" extends ").append(typeName(wildcard.getUpperBounds()[0]));
            }
            return result.toString();
        }
        return type.getTypeName();
    }

    /** A connection state and protocol id, as reported by the data generator. */
    private record State(String name, int protocolId) {
    }
}

