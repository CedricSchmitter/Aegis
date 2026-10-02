package dev.aegis.documents.application.ports;

import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.Optional;

/**
 * Durable scheduling of archival work. Scheduling must take part in the caller's database transaction so that a
 * document record and its job are created atomically.
 */
public interface ProcessingScheduler {
    /** Schedules immediate archival if no job for the document exists yet. */
    void scheduleArchival(DocumentId id);

    /** Next planned execution of the archival job, if one is scheduled. */
    Optional<Instant> nextAttempt(DocumentId id);

    /** Schedules the permanent deletion of a trashed document (ADR-0021) if no such job exists yet. */
    void schedulePurge(DocumentId id);

    /** Next planned execution of the purge job, if one is scheduled. */
    Optional<Instant> nextPurgeAttempt(DocumentId id);

    /** Schedules the next step of a bulk analysis at {@code at}, in the caller's transaction (issue #115). */
    void scheduleBulkAnalysis(java.util.UUID bulkId, Instant at);

    /** Schedules the page editor job of an operation (ADR-0024) at {@code at}, in the caller's transaction. */
    void schedulePageEdit(java.util.UUID pageEditId, Instant at);
}
