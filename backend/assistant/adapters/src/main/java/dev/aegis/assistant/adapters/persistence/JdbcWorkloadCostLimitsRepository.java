package dev.aegis.assistant.adapters.persistence;

import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.WorkloadCostLimits;
import dev.aegis.assistant.domain.WorkloadCostLimitsRepository;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One row per workload whose cost limits a member set. The first change inserts version 1; later changes are
 * version-checked updates. Two members changing the same workload at once: one wins, the other gets a stale version.
 */
@Component
public class JdbcWorkloadCostLimitsRepository implements WorkloadCostLimitsRepository {
    private static final String SELECT = """
            SELECT workload, daily_limit, run_limit, currency, version, changed_by, changed_at FROM ai_cost_limits""";

    private final JdbcClient jdbc;

    JdbcWorkloadCostLimitsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkloadCostLimits> find(Workload workload) {
        return jdbc.sql(SELECT + " WHERE workload = ?").param(workload.name()).query(this::map).optional();
    }

    @Override
    public Map<Workload, WorkloadCostLimits> findAll() {
        Map<Workload, WorkloadCostLimits> all = new EnumMap<>(Workload.class);
        jdbc.sql(SELECT).query(this::map).list().forEach(limits -> all.put(limits.workload(), limits));
        return all;
    }

    @Override
    public void save(WorkloadCostLimits limits) {
        Money daily = limits.dailyLimit().orElseThrow();
        Money run = limits.runLimit().orElseThrow();
        UUID changedBy = limits.changedBy().map(MemberId::value).orElse(null);
        Timestamp changedAt = limits.changedAt().map(Timestamp::from).orElse(null);
        int stored;
        if (limits.version() == 1) {
            stored = jdbc.sql("""
                    INSERT INTO ai_cost_limits (workload, daily_limit, run_limit, currency, version, changed_by,
                        changed_at)
                    VALUES (?, ?, ?, ?, 1, ?, ?) ON CONFLICT (workload) DO NOTHING""")
                    .params(limits.workload().name(), daily.amount(), run.amount(),
                            daily.currency().getCurrencyCode(), changedBy, changedAt).update();
        } else {
            stored = jdbc.sql("""
                    UPDATE ai_cost_limits SET daily_limit = ?, run_limit = ?, currency = ?, version = ?,
                        changed_by = ?, changed_at = ?
                     WHERE workload = ? AND version = ?""")
                    .params(daily.amount(), run.amount(), daily.currency().getCurrencyCode(), limits.version(),
                            changedBy, changedAt, limits.workload().name(), limits.version() - 1).update();
        }
        if (stored == 0) {
            long current = jdbc.sql("SELECT version FROM ai_cost_limits WHERE workload = ?")
                    .param(limits.workload().name()).query(Long.class).optional().orElse(0L);
            throw new StaleVersionException("aiCostLimits", limits.workload().name(), limits.version() - 1,
                    current);
        }
    }

    private WorkloadCostLimits map(ResultSet row, int index) throws SQLException {
        Currency currency = Currency.getInstance(row.getString("currency").strip());
        UUID changedBy = row.getObject("changed_by", UUID.class);
        Timestamp changedAt = row.getTimestamp("changed_at");
        return WorkloadCostLimits.restore(Workload.valueOf(row.getString("workload")),
                new Money(row.getBigDecimal("daily_limit"), currency), new Money(row.getBigDecimal("run_limit"),
                        currency), row.getLong("version"), changedBy == null ? null : new MemberId(changedBy),
                changedAt == null ? null : changedAt.toInstant());
    }
}
