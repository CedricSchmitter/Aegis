package dev.aegis.documents.application;

import dev.aegis.shared.DocumentId;
import java.time.Instant;

/**
 * Job-facing use cases. Each call is a short transaction; the archive round-trip happens between calls so that no
 * database transaction waits on Paperless.
 */
public interface ArchivalUseCases {

    sealed interface ArchivalStep permits Submit, Poll, Finished {
    }

    /** Resubmit the retained original. */
    record Submit(DocumentId id, String stagingPath, String title, String originalFilename) implements ArchivalStep {
    }

    /** Ask the archive whether the accepted submission finished. */
    record Poll(DocumentId id, String taskId, Instant submittedAt) implements ArchivalStep {
    }

    /** Nothing left to do: archived, failed permanently or record missing. */
    record Finished(DocumentId id, String reason) implements ArchivalStep {
    }

    record FailureDecision(boolean retry, int attempts) {
    }

    ArchivalStep prepare(DocumentId id);

    void recordSubmission(DocumentId id, String taskId);

    void recordArchived(DocumentId id, int paperlessDocumentId);

    FailureDecision recordFailure(DocumentId id, String code, String message, boolean retryable);
}
