package com.terila.extractor.extractors.tags;

// Written by AI
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.gameevent.GameEvent;

/** Writes {@code tags/game_events.json} (e.g. {@code vibrations}, {@code ignore_vibrations}). */
public final class GameEventTagExtractor extends TagExtractor<GameEvent> {

    public GameEventTagExtractor() {
        super("game_event_tags", "tags/game_events.json", Registries.GAME_EVENT);
    }
}
