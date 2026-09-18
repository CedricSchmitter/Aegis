package dev.aegis.documents.application.ports;

/**
 * Serializes catalog changes within the surrounding transaction. Structural changes (folders, vocabulary, merges)
 * take the exclusive lock; document classification edits and captures take the shared lock so that they never
 * interleave with a merge or folder deletion. Locks are released when the transaction ends.
 */
public interface CatalogLock {
    void exclusive();

    void shared();
}
