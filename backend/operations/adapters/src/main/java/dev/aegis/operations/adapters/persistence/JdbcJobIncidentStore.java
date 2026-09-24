package dev.aegis.operations.adapters.persistence;

import dev.aegis.operations.application.ports.JobIncidentStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Job incidents in {@code job_health_incidents}; a partial unique index keeps one open incident per job. */
@Component
public class JdbcJobIncidentStore implements JobIncidentStore {
    private final JdbcClient jdbc;

    public JdbcJobIncidentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<JobIncident> findOpen(String jobName) {
        return jdbc.sql("""
                SELECT id, job_name, state, last_success_at, opened_at FROM job_health_incidents
                WHERE job_name = :job AND resolved_at IS NULL
                """).param("job", jobName).query((rs, row) -> incident(rs)).optional();
    }

    @Override
    public JobIncident openIfAbsent(String jobName, String state, Instant lastSuccessAt, Instant now) {
        jdbc.sql("""
                INSERT INTO job_health_incidents (id, job_name, state, last_success_at, opened_at)
                VALUES (:id, :job, :state, :lastSuccess, :now)
                ON CONFLICT (job_name) WHERE resolved_at IS NULL DO NOTHING
                """).param("id", UUID.randomUUID()).param("job", jobName).param("state", state)
                .param("lastSuccess", timestamp(lastSuccessAt)).param("now", timestamp(now)).update();
        return findOpen(jobName).orElseThrow(() -> new IllegalStateException("no open incident for " + jobName));
    }

    @Override
    public void resolve(UUID incidentId, Instant now) {
        jdbc.sql("UPDATE job_health_incidents SET resolved_at = :now WHERE id = :id AND resolved_at IS NULL")
                .param("now", timestamp(now)).param("id", incidentId).update();
    }

    private static JobIncident incident(ResultSet rs) throws SQLException {
        return new JobIncident(rs.getObject("id", UUID.class), rs.getString("job_name"), rs.getString("state"),
                instant(rs.getTimestamp("last_success_at")), instant(rs.getTimestamp("opened_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
