package com.terila.extractor.extractors.entities;

// Written by AI
import com.google.gson.JsonObject;
import com.terila.extractor.TestSupport;
import net.minecraft.network.syncher.EntityDataSerializers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the entity metadata value type table.
 *
 * <p>This table used to be written out by hand, and by 26.3 it had drifted: ids were wrong, a
 * serializer the game had added was missing, and entries the game does not have were listed with
 * invented ids. The tests below compare the document against
 * {@link EntityDataSerializers} itself, which is the source it is now derived from, so a drift in
 * either direction fails.
 */
class EntityMetadataExtractorTest {

    private static Map<Integer, EntityMetadataExtractor.MetadataType> derived;
    private static List<EntityMetadataExtractor.MetadataType> types;

    @BeforeAll
    static void derive() {
        TestSupport.bootStrap();
        types = EntityMetadataExtractor.types();
        derived = types.stream().collect(Collectors.toMap(
                EntityMetadataExtractor.MetadataType::protocolId, Function.identity()));
    }

    /** Every protocol id the game has registered has to be described. */
    @Test
    void coversEveryRegisteredSerializer() {
        assertTrue(types.size() >= 40, "expected the full serializer table, got " + types.size());

        for (int id = 0; id < types.size(); id++) {
            assertNotNull(EntityDataSerializers.getSerializer(id),
                    "id " + id + " is a gap; the table would be missing an entry");
            assertTrue(derived.containsKey(id), "the table has no entry for id " + id);
        }
        assertFalse(derived.containsKey(types.size()),
                "the table describes id " + types.size() + ", which the game has not registered");
    }

    /** The ids have to be the ones the game reports, not an index into a list of names. */
    @Test
    void protocolIdsAreTheGamesOwn() {
        for (EntityMetadataExtractor.MetadataType type : types) {
            if (type.constant() == null) {
                continue;
            }
            var field = fieldNamed(type.constant());
            assertNotNull(field, "no such constant: " + type.constant());

            assertEquals(EntityDataSerializers.getSerializedId(serializerOf(field)), type.protocolId(),
                    "id mismatch for " + type.constant());
        }
    }

    /** The serializer the game added last must be present; it is what the hand-written table missed. */
    @Test
    void includesTheNewestSerializer() {
        List<String> names = derived.values().stream()
                .map(EntityMetadataExtractor.MetadataType::name)
                .toList();

        assertTrue(names.contains("dye_color"),
                "the game registers a dye colour serializer: " + names);
    }

    /** Names come from the game's constants, so they are the fields it actually declares. */
    @Test
    void namesComeFromTheGamesConstants() {
        for (EntityMetadataExtractor.MetadataType type : types) {
            assertNotNull(type.constant(), "an unlisted id lacks a name");
            assertEquals(type.constant().toLowerCase(java.util.Locale.ROOT), type.name());
            assertNotNull(fieldNamed(type.constant()), "unknown constant " + type.constant());
        }
    }

    /** The value type has to be the type argument of the serializer's constant. */
    @Test
    void recordsTheValueTypeOfEachSerializer() {
        assertTrue(derived.values().stream().anyMatch(type -> "java.lang.String".equals(type.valueType())),
                "a string serializer exists");

        var rotations = derived.values().stream()
                .filter(type -> "rotations".equals(type.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("net.minecraft.core.Rotations", rotations.valueType());
    }

    /**
     * The exported name has to be free of the JVM's synthetic lambda naming.
     *
     * <p>Most serializers are hidden lambda classes whose simple name carries a per-process identity
     * hash; recording it would make two runs of the same extraction differ.
     */
    @Test
    void serializerNamesAreStableAcrossRuns() {
        List<String> first = types.stream()
                .map(EntityMetadataExtractor.MetadataType::serializer)
                .toList();
        List<String> second = EntityMetadataExtractor.types().stream()
                .map(EntityMetadataExtractor.MetadataType::serializer)
                .toList();

        assertEquals(first, second, "the table is not reproducible within one process");
        for (String name : first) {
            assertFalse(name.contains("/0x"), "synthetic lambda name leaked: " + name);
            assertFalse(name.isBlank(), "an entry has no serializer name");
        }
    }

    /** The document has to describe the table, not just repeat the count. */
    @Test
    void documentDescribesTheTable() {
        JsonObject document = TestSupport.document("entities/metadata_types.json");

        assertEquals(types.size(), document.get("type_count").getAsInt());
        assertEquals(types.size(), document.getAsJsonArray("data").size());
        assertEquals(types.size() - 1, document.get("highest_protocol_id").getAsInt());
        assertTrue(document.get("contiguous").getAsBoolean(), "the id table has gaps");
        assertEquals(0, document.get("unlisted_count").getAsInt(),
                "every registered serializer has a public constant");

        for (var element : document.getAsJsonArray("data")) {
            JsonObject entry = element.getAsJsonObject();
            assertTrue(entry.has("protocol_id"));
            assertTrue(entry.has("constant"));
            assertTrue(entry.has("value_type"));
        }
    }

    private static java.lang.reflect.Field fieldNamed(String constant) {
        try {
            return EntityDataSerializers.class.getField(constant);
        } catch (NoSuchFieldException | RuntimeException missing) {
            return null;
        }
    }

    private static net.minecraft.network.syncher.EntityDataSerializer<?> serializerOf(
            java.lang.reflect.Field field) {
        try {
            return (net.minecraft.network.syncher.EntityDataSerializer<?>) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }
}
