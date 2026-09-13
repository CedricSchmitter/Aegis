package dev.aegis.operations.application.ports;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.shared.Actor;

/** One integration's status probe. Implementations must not throw; they report UNAVAILABLE with a detail. */
public interface IntegrationStatusSource {
    /** Display order on the status page. */
    int order();

    IntegrationStatus check(Actor actor);
}
