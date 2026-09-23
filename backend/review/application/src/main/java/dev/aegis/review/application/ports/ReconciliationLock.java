package dev.aegis.review.application.ports;

/**
 * Serializes review reconciliations within the surrounding transaction and releases the lock when it ends. The
 * background job and a member opening the inbox then never read the same missing card and both insert it; the
 * later one reads the cards the earlier one committed.
 */
public interface ReconciliationLock {
    void exclusive();
}
