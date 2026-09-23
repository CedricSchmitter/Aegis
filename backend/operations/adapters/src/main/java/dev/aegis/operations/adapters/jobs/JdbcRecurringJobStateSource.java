package dev.aegis.operations.adapters.jobs;

import dev.aegis.operations.application.ports.RecurringJobStateSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads db-scheduler's persisted state of recurring jobs from {@code scheduled_tasks}: run times, failure count and
 * the next run. It never selects {@code task_data}, so no job payload leaves the table.
 */
@Component
public class JdbcRecurringJobStateSource implements RecurringJobStateSource {
    /** db-scheduler stores a recurring task under this fixed instance id ({@code RecurringTask.INSTANCE}). */
    static final String RECURRING_INSTANCE = "recurring";

    private final JdbcClient jdbc;

    public JdbcRecurringJobStateSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, RecurringJobState> states(Collection<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<String, RecurringJobState> states = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT task_name, last_success, last_failure, consecutive_failures, execution_time
                FROM scheduled_tasks WHERE task_instance = :instance AND task_name IN (:names)
                """).param("instance", RECURRING_INSTANCE).param("names", List.copyOf(names))
                .query((rs, row) -> Map.entry(rs.getString("task_name"), state(rs)))
                .list().forEach(entry -> states.put(entry.getKey(), entry.getValue()));
        return states;
    }

    private static RecurringJobState state(ResultSet rs) throws SQLException {
        return new RecurringJobState(instant(rs, "last_success"), instant(rs, "last_failure"),
                rs.getInt("consecutive_failures"), instant(rs, "execution_time"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
