package com.terila.extractor.util;

// Written by AI
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.function.Supplier;

/**
 * Text component helpers for names, translations and advancement displays.
 *
 * <h2>Why every resolution goes through this class</h2>
 *
 * <p>{@link Component#getString()} resolves a translatable component against the language table and
 * memoises the result <em>inside the component instance</em>. That memoisation is not synchronised,
 * and the game reuses component instances ({@link net.minecraft.world.entity.EntityType#getDescription()}
 * is a cached field, as are the item name components): two extractor threads resolving the same
 * component at the same time let one of them read the not-yet-filled memo and see an <em>empty</em>
 * string. The output then silently loses that name.
 *
 * <p>Resolution is therefore serialised behind one lock and an empty result is retried once: while
 * the lock is held no other resolution can be half-way through filling the memo, so the second pass
 * always observes the finished text. Both the lookup and the retry are in-memory map reads, so the
 * cost is noise next to the JSON encoding done per entry.
 */
public final class TextUtil {

    /** Guards the game's unsynchronised component memoisation. */
    private static final Object RESOLVE_LOCK = new Object();

    private TextUtil() {
    }

    /**
     * Flattened, formatting-free text.
     *
     * <p>Thread-safe; see the class comment for why that needs a lock.
     */
    public static String plain(Component component) {
        if (component == null) {
            return null;
        }
        return resolve(() -> component);
    }

    /** Flattened text capped to {@code limit} characters. Thread-safe. */
    public static String plain(Component component, int limit) {
        if (component == null) {
            return null;
        }
        synchronized (RESOLVE_LOCK) {
            String text = component.getString(limit);
            return text.isEmpty() ? component.getString(limit) : text;
        }
    }

    /**
     * Builds and resolves a component under the resolution lock.
     *
     * <p>Use this whenever the component itself comes from a cached game field
     * ({@code item.getName(stack)}, {@code block.getName()}, {@code type.getDescription()}), so that
     * neither the construction nor the memoised lookup can interleave with another extraction thread.
     */
    public static String resolve(Supplier<Component> component) {
        synchronized (RESOLVE_LOCK) {
            String text = component.get().getString();
            // An empty first read is the signature of the memoisation race; one retry is enough.
            return text.isEmpty() ? component.get().getString() : text;
        }
    }

    /**
     * Full component tree as JSON (retains translate keys, styles and children) so the data
     * generator can decide between resolving a translation or keeping the key.
     */
    public static JsonElement toJson(Component component) {
        if (component == null) {
            return null;
        }
        return ComponentSerialization.CODEC
                .encodeStart(JsonOps.INSTANCE, component)
                .result()
                .orElse(null);
    }

    /** Translation key of the first translatable element, if any. */
    public static String translationKey(Component component) {
        if (component == null) {
            return null;
        }
        return component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable
                ? translatable.getKey()
                : null;
    }
}
