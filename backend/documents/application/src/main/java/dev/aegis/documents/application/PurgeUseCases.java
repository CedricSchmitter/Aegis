package dev.aegis.documents.application;

import dev.aegis.shared.DocumentId;

/**
 * Job-facing steps of a document's permanent deletion (ADR-0021). Each call is a short transaction; the archive and
 * file system work happens between the calls, so no database transaction waits on Paperless.
 */
public interface PurgeUseCases {

    sealed interface PurgeStep permits Purge, Finished {
    }

    /**
     * Delete what lies outside the database, then {@link #complete} the purge. {@code paperlessDocumentId} is the
     * archive document to delete, or null when there is none or another Aegis document still uses it.
     * {@code unrecordedTaskId} is a consumption task of a failed archival whose result Aegis never recorded: the job
     * asks the archive about it, so a document the archive created late is deleted as well.
     */
    record Purge(DocumentId id, Integer paperlessDocumentId, String unrecordedTaskId, String stagingPath)
            implements PurgeStep {
    }

    /** Nothing left to do: the record is gone, its purge failed for good, or no purge was requested. */
    record Finished(DocumentId id, String reason) implements PurgeStep {
    }

    record FailureDecision(boolean retry, int attempts) {
    }

    PurgeStep prepare(DocumentId id);

    /**
     * Whether another Aegis document is archived as this archive document, which then stays. Checked once more right
     * before the archive deletion of a document the archive created late.
     */
    boolean archiveDocumentShared(DocumentId id, int paperlessDocumentId);

    FailureDecision recordFailure(DocumentId id, String code, boolean retryable);

    /** Deletes the record and everything the documents context keeps for it, once the outside work is done. */
    void complete(DocumentId id);
}
