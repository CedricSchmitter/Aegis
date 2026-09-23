package dev.aegis.review.adapters.persistence;

import dev.aegis.review.application.ports.ReconciliationLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** PostgreSQL advisory lock held until the surrounding transaction ends. */
@Component
public class PgReconciliationLock implements ReconciliationLock {
    /** Constant key of the review reconciliation lock; unrelated to any table. */
    static final long KEY = 748_212_006L;

    private final JdbcClient jdbc;

    PgReconciliationLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void exclusive() {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(KEY).query((rs, row) -> Boolean.TRUE).single();
    }
}
