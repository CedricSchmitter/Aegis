package dev.aegis.assistant.application.api;

import dev.aegis.shared.Actor;

/** Operational AI state for the status page. */
public interface AiHealthQuery {
    AiHealth health(Actor actor);
}
