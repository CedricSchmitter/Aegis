package dev.aegis.operations.application.ports;

import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.shared.Actor;
import java.util.Optional;

/**
 * Probes one area of the status overview, such as the database or the archive. Implementations must not throw; a
 * probe that fails anyway is reported as a problem of its area. An integration that is not set up returns empty and is
 * not listed; it appears once it is configured.
 */
public interface HealthCheckSource {
    /** The area the probe reports, also used when the probe itself fails. */
    String area();

    /** Position in the overview; the backup and storage lines sit at 30 and 35. */
    int order();

    Optional<HealthCheck> check(Actor actor);
}
