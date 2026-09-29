package dev.aegis.assistant.adapters.persistence;

import dev.aegis.assistant.domain.ModelFamily;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.WorkloadModelChoice;
import dev.aegis.assistant.domain.WorkloadModelChoiceRepository;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One row per workload whose model family a member changed. The first change inserts version 1; later changes are
 * version-checked updates. Two members changing the same workload at once: one wins, the other gets a stale version.
 */
@Component
public class JdbcWorkloadModelChoiceRepository implements WorkloadModelChoiceRepository {
    private static final String SELECT = "SELECT workload, family, version, changed_by, changed_at FROM ai_model_choices";

    private final JdbcClient jdbc;

    JdbcWorkloadModelChoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkloadModelChoice> find(Workload workload) {
        return jdbc.sql(SELECT + " WHERE workload = ?").param(workload.name()).query(this::map).optional();
    }

    @Override
    public Map<Workload, WorkloadModelChoice> findAll() {
        Map<Workload, WorkloadModelChoice> choices = new EnumMap<>(Workload.class);
        jdbc.sql(SELECT).query(this::map).list().forEach(choice -> choices.put(choice.workload(), choice));
        return choices;
    }

    @Override
    public void save(WorkloadModelChoice choice) {
        UUID changedBy = choice.changedBy().map(MemberId::value).orElse(null);
        Timestamp changedAt = choice.changedAt().map(Timestamp::from).orElse(null);
        int stored;
        if (choice.version() == 1) {
            stored = jdbc.sql("""
                    INSERT INTO ai_model_choices (workload, family, version, changed_by, changed_at)
                    VALUES (?, ?, 1, ?, ?) ON CONFLICT (workload) DO NOTHING""")
                    .params(choice.workload().name(), choice.family().name(), changedBy, changedAt).update();
        } else {
            stored = jdbc.sql("""
                    UPDATE ai_model_choices SET family = ?, version = ?, changed_by = ?, changed_at = ?
                     WHERE workload = ? AND version = ?""")
                    .params(choice.family().name(), choice.version(), changedBy, changedAt,
                            choice.workload().name(), choice.version() - 1).update();
        }
        if (stored == 0) {
            long current = jdbc.sql("SELECT version FROM ai_model_choices WHERE workload = ?")
                    .param(choice.workload().name()).query(Long.class).optional().orElse(0L);
            throw new StaleVersionException("aiModelChoice", choice.workload().name(), choice.version() - 1,
                    current);
        }
    }

    private WorkloadModelChoice map(ResultSet row, int index) throws SQLException {
        UUID changedBy = row.getObject("changed_by", UUID.class);
        Timestamp changedAt = row.getTimestamp("changed_at");
        return WorkloadModelChoice.restore(Workload.valueOf(row.getString("workload")),
                ModelFamily.valueOf(row.getString("family")), row.getLong("version"),
                changedBy == null ? null : new MemberId(changedBy), changedAt == null ? null : changedAt.toInstant());
    }
}
