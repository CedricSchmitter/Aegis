package dev.aegis.documents.domain;

/**
 * Archival state of a captured document. Receipt is durable in every state; only {@link #ARCHIVED} means Paperless
 * holds the original. Retries stay in RECEIVED or SUBMITTED with a recorded failure; FAILED needs a manual retry.
 */
public enum ProcessingState {
    /** Stored durably in ingress and recorded; archival job scheduled. */
    RECEIVED,
    /** Paperless accepted the file; waiting for consumption to finish. */
    SUBMITTED,
    /** Paperless holds the document; the Paperless id is known. */
    ARCHIVED,
    /** Retries are exhausted or the failure is not retryable; the original stays available in ingress. */
    FAILED;

    public boolean isTerminal() {
        return this == ARCHIVED;
    }
}
