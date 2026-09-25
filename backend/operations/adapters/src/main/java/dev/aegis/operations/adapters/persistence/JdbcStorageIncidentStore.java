package dev.aegis.operations.adapters.persistence;

import dev.aegis.operations.application.ports.StorageIncidentStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Storage incidents in {@code storage_health_incidents}; a partial unique index keeps one open incident per subject. */
@Component
public class JdbcStorageIncidentStore implements StorageIncidentStore {
    private final JdbcClient jdbc;

    public JdbcStorageIncidentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StorageIncident> findOpen(String subject) {
        return jdbc.sql("""
                SELECT id, subject, condition, failure_code, last_success_at, free_bytes, total_bytes, opened_at
                FROM storage_health_incidents WHERE subject = :subject AND resolved_at IS NULL
                """).param("subject", subject).query((rs, row) -> incident(rs)).optional();
    }

    @Override
    public StorageIncident openIfAbsent(StorageIncident proposed) {
        jdbc.sql("""
                INSERT INTO storage_health_incidents
                    (id, subject, condition, failure_code, last_success_at, free_bytes, total_bytes, opened_at)
                VALUES (:id, :subject, :condition, :failureCode, :lastSuccess, :free, :total, :opened)
                ON CONFLICT (subject) WHERE resolved_at IS NULL DO NOTHING
                """).param("id", proposed.id()).param("subject", proposed.subject())
                .param("condition", proposed.condition()).param("failureCode", proposed.failureCode())
                .param("lastSuccess", timestamp(proposed.lastSuccessAt())).param("free", proposed.freeBytes())
                .param("total", proposed.totalBytes()).param("opened", timestamp(proposed.openedAt())).update();
        return findOpen(proposed.subject())
                .orElseThrow(() -> new IllegalStateException("no open incident for " + proposed.subject()));
    }

    @Override
    public void resolve(UUID incidentId, Instant now) {
        jdbc.sql("UPDATE storage_health_incidents SET resolved_at = :now WHERE id = :id AND resolved_at IS NULL")
                .param("now", timestamp(now)).param("id", incidentId).update();
    }

    private static StorageIncident incident(ResultSet rs) throws SQLException {
        return new StorageIncident(rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("condition"),
                rs.getString("failure_code"), instant(rs.getTimestamp("last_success_at")),
                rs.getObject("free_bytes", Long.class), rs.getObject("total_bytes", Long.class),
                instant(rs.getTimestamp("opened_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
