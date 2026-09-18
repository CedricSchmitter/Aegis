package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.CatalogLock;

/** Test double: the real lock is a PostgreSQL advisory lock verified by a concurrency integration test. */
public class NoOpCatalogLock implements CatalogLock {
    @Override
    public void exclusive() {
        // nothing to serialize in a single-threaded test
    }

    @Override
    public void shared() {
        // nothing to serialize in a single-threaded test
    }
}
