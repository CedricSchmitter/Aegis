package dev.aegis.operations.application.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Incidents of the external backup and of low disk space (ADR-0015). A subject ({@code backup}, {@code disk:data},
 * {@code disk:backup}) has at most one open incident; it closes on recovery, and a later outage opens a new one.
 */
public interface StorageIncidentStore {
    Optional<StorageIncident> findOpen(String subject);

    /**
     * Opens an incident for the subject unless one is already open, and returns the open one. Repeated and
     * concurrent calls return the same incident.
     */
    StorageIncident openIfAbsent(StorageIncident proposed);

    void resolve(UUID incidentId, Instant now);

    /**
     * An incident with what was observed when it opened: for the backup its state, failure code and last success,
     * for a disk its free and total bytes. Values that do not apply are null.
     */
    record StorageIncident(UUID id, String subject, String condition, String failureCode, Instant lastSuccessAt,
            Long freeBytes, Long totalBytes, Instant openedAt) {
    }
}
