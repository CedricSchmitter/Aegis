package dev.aegis.assistant.adapters.persistence;

import dev.aegis.assistant.application.ports.AiLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** PostgreSQL advisory lock held until the surrounding transaction ends. */
@Component
public class PgAiLock implements AiLock {
    /** Constant key of the AI admission lock; unrelated to any table. */
    static final long KEY = 748_212_005L;

    private final JdbcClient jdbc;

    PgAiLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void exclusive() {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(KEY).query((rs, row) -> Boolean.TRUE).single();
    }
}
