package dev.aegis.operations.application.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Incidents of recurring jobs that stopped running (ADR-0014). A job has at most one open incident; it closes when the
 * job succeeds again, and a later outage opens a new one.
 */
public interface JobIncidentStore {
    Optional<JobIncident> findOpen(String jobName);

    /**
     * Opens an incident for the job unless one is already open, and returns the open one. Repeated and concurrent
     * calls return the same incident.
     */
    JobIncident openIfAbsent(String jobName, String state, Instant lastSuccessAt, Instant now);

    void resolve(UUID incidentId, Instant now);

    /**
     * An open incident: the job's health state when it was opened (STALE or NOT_SCHEDULED) and its last success
     * before the outage, null when it never succeeded or had no schedule.
     */
    record JobIncident(UUID id, String jobName, String state, Instant lastSuccessAt, Instant openedAt) {
    }
}
