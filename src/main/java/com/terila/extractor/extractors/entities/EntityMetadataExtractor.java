package com.terila.extractor.extractors.entities;

// Written by AI
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.terila.extractor.core.AbstractExtractor;
import com.terila.extractor.core.DataType;
import com.terila.extractor.core.ExtractorContext;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Writes {@code entities/metadata_types.json}: the entity metadata value types and the protocol id
 * each is sent under.
 *
 * <p>Minecraft does not document this table, but it does expose it: {@link EntityDataSerializers}
 * registers every serializer and hands back the protocol id with
 * {@link EntityDataSerializers#getSerializedId(EntityDataSerializer)}. The table is therefore
 * <em>derived from the running game</em>, exactly like the registry dumps, rather than written out by
 * hand.
 *
 * <p>That distinction is not cosmetic. The previous version of this extractor carried a hand-written
 * list of 43 entries, and by 26.3 it was wrong in three ways: ids had drifted (copper golem state is
 * 37, not 33), serializers had been added that the list did not know about ({@code DYE_COLOR}), and
 * entries the list claimed existed — {@code sulfur_cube_variant}, {@code below_name_distance} — are
 * not in the table at all. Deriving it means a new Minecraft release is covered without an edit, and
 * a serializer that appears or disappears is visible in the output instead of silently absent.
 *
 * <p>The public constant names came from {@code EntityDataSerializers}' own fields, and the generic
 * value type from each field's declared type argument, so the document names what the serializer
 * carries (a {@code Rotations}, an {@code Optional<BlockPos>}, a variant holder).
 */
public final class EntityMetadataExtractor extends AbstractExtractor {

    /** Placeholder for a protocol id that has a serializer but no public constant. */
    private static final String UNLISTED = "(unlisted)";

    /**
     * One metadata value type.
     *
     * @param protocolId the wire id the serializer is registered under.
     * @param constant   the game's own constant name, e.g. {@code OPTIONAL_BLOCK_POS}.
     * @param name       lower-cased form of {@code constant}, e.g. {@code optional_block_pos}.
     * @param valueType  the Java type the serializer carries, e.g. {@code java.util.Optional<...>}.
     * @param serializer simple class name of the serializer that does the decoding.
     */
    public record MetadataType(int protocolId, String constant, String name, String valueType,
                               String serializer) {

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("protocol_id", protocolId);
            if (constant != null) {
                json.addProperty("constant", constant);
                json.addProperty("name", name);
            }
            if (valueType != null) {
                json.addProperty("value_type", valueType);
            }
            json.addProperty("serializer", serializer);
            return json;
        }
    }

    public EntityMetadataExtractor() {
        super("entity_metadata", DataType.ENTITIES, "entities/metadata_types.json");
    }

    @Override
    public int extract(ExtractorContext context) throws IOException {
        List<MetadataType> types = types();

        JsonArray data = new JsonArray();
        types.forEach(type -> data.add(type.toJson()));

        int highest = types.stream().mapToInt(MetadataType::protocolId).max().orElse(-1);
        TreeSet<Integer> present = new TreeSet<>();
        types.forEach(type -> present.add(type.protocolId()));

        JsonObject root = new JsonObject();
        root.add("_meta", context.meta(name(), types.size()));
        root.addProperty("source", "net.minecraft.network.syncher.EntityDataSerializers");
        root.addProperty("type_count", types.size());
        root.addProperty("highest_protocol_id", highest);
        // A gap means the game reserved an id without registering a serializer; that is worth seeing.
        root.addProperty("contiguous", present.size() == highest + 1);
        root.addProperty("unlisted_count", (int) types.stream().filter(t -> t.constant() == null).count());
        root.add("data", data);
        write(context, root);
        return types.size();
    }

    /**
     * The full serializer table, ordered by protocol id.
     *
     * <p>Reads the game's public constants reflectively only to learn their <em>names</em>; the ids and
     * the value types come from {@link EntityDataSerializers} itself. Deriving the names this way keeps
     * the document self-describing without a table to maintain.
     */
    public static List<MetadataType> types() {
        Map<EntityDataSerializer<?>, Field> fields = serializerFields();
        int highest = fields.values().stream()
                .mapToInt(field -> EntityDataSerializers.getSerializedId(serializerOf(field)))
                .max()
                .orElse(-1);

        List<MetadataType> types = new ArrayList<>();
        for (int id = 0; id <= highest; id++) {
            EntityDataSerializer<?> serializer = EntityDataSerializers.getSerializer(id);
            if (serializer == null) {
                continue;
            }
            Field field = fields.get(serializer);
            String constant = field == null ? null : field.getName();
            types.add(new MetadataType(
                    id,
                    constant,
                    constant == null ? null : constant.toLowerCase(Locale.ROOT),
                    field == null ? null : valueTypeOf(field),
                    serializerName(serializer)));
        }
        types.sort(Comparator.comparingInt(MetadataType::protocolId));
        return List.copyOf(types);
    }

    /** Public serializer constants keyed by identity, so lookups match the game's own instances. */
    private static Map<EntityDataSerializer<?>, Field> serializerFields() {
        Map<EntityDataSerializer<?>, Field> bySerializer = new IdentityHashMap<>();
        for (Field field : EntityDataSerializers.class.getFields()) {
            if (!EntityDataSerializer.class.isAssignableFrom(field.getType())) {
                continue;
            }
            EntityDataSerializer<?> serializer = serializerOf(field);
            if (serializer != null) {
                bySerializer.put(serializer, field);
            }
        }
        return bySerializer;
    }

    private static EntityDataSerializer<?> serializerOf(Field field) {
        try {
            return (EntityDataSerializer<?>) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * A stable, readable name for the serializer.
     *
     * <p>Most entries are lambdas produced by {@code EntityDataSerializer.forValueType(...)}. The JVM
     * names those after the defining class plus a per-process identity hash
     * ({@code EntityDataSerializer$$Lambda/0x000000008a752c38}), which would differ between runs and
     * break the byte-identical guarantee, so the synthetic part is cut off and the defining class is
     * reported instead.
     */
    private static String serializerName(EntityDataSerializer<?> serializer) {
        Class<?> type = serializer.getClass();
        String name = type.getSimpleName();
        int slash = name.indexOf('/');
        if (slash >= 0) {
            name = name.substring(0, slash);
        }
        int lambda = name.indexOf("$$Lambda");
        if (lambda >= 0) {
            name = name.substring(0, lambda);
        }
        if (!name.isEmpty()) {
            return name;
        }
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing != null ? enclosing.getSimpleName() : type.getName();
    }

    /** The type argument of {@code EntityDataSerializer<T>}, e.g. {@code Optional<BlockPos>}. */
    private static String valueTypeOf(Field field) {
        Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
            return parameterized.getActualTypeArguments()[0].getTypeName();
        }
        return null;
    }
}
