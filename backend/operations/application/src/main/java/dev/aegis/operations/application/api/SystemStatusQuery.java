package dev.aegis.operations.application.api;

import dev.aegis.shared.Actor;

/** Truthful operational status for members. */
public interface SystemStatusQuery {
    SystemStatus status(Actor actor);
}
