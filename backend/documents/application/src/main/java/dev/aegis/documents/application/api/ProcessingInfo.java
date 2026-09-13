package dev.aegis.documents.application.api;

import java.time.Instant;

/**
 * Truthful processing view. {@code state} is RECEIVED, SUBMITTED, ARCHIVED or FAILED; {@code retryScheduled} tells
 * whether a job will try again and when.
 */
public record ProcessingInfo(String state, int attempts, String lastErrorCode, String lastErrorMessage,
        Instant lastErrorAt, boolean retryScheduled, Instant nextAttemptAt, Integer paperlessDocumentId,
        Instant archivedAt) {
}
