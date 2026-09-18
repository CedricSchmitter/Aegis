package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.application.ports.CatalogLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL advisory locks held until the surrounding transaction ends. Structural catalog changes take the
 * exclusive lock, classification edits the shared one, so a merge or folder deletion never interleaves with an
 * assignment and two concurrent folder moves cannot form a cycle.
 */
@Component
public class PgCatalogLock implements CatalogLock {
    /** Constant key of the document catalog lock; unrelated to any table. */
    static final long KEY = 748_212_001L;

    private final JdbcClient jdbc;

    PgCatalogLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void exclusive() {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(KEY).query((rs, row) -> Boolean.TRUE).single();
    }

    @Override
    public void shared() {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(?)").param(KEY).query((rs, row) -> Boolean.TRUE).single();
    }
}
