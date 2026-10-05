package dev.aegis.assistant.adapters.persistence;

import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.RunFailure;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.RunStep;
import dev.aegis.assistant.domain.SourceRef;
import dev.aegis.assistant.domain.StepKind;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs in PostgreSQL with explicit SQL. Updates are version-checked; audit steps are appended and never changed.
 * Sources and parameters are small JSON documents owned by the run.
 */
@Component
public class JdbcAiRunRepository implements AiRunRepository {
    private static final String COLUMNS = """
            id, workload, idempotency_key, retry_of, requested_by, requested_at, provider, model,
            parameters::text AS parameters_json, sources::text AS sources_json, budget_max_duration_ms,
            budget_max_cost, currency, budget_max_model_calls, budget_max_tool_calls, budget_max_output_tokens,
            budget_max_attempts, state, attempt, attempt_started_at, next_attempt_at, finished_at, cancel_requested,
            cancel_requested_by, model_calls, tool_calls, denied_tool_calls, attempt_model_calls, attempt_tool_calls,
            input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost, output, failure_category,
            failure_code, failure_message, failure_at, step_count, budget_waits, slot_offered_at, version""";
    private static final String SELECT_RUNS = "SELECT " + COLUMNS + " FROM ai_runs ";
    private static final TypeReference<Map<String, String>> PARAMETERS = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> SOURCES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcAiRunRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<AiRun> findById(RunId id) {
        return jdbc.sql(SELECT_RUNS + "WHERE id = ?").param(id.value()).query(this::map).optional();
    }

    @Override
    public Optional<AiRun> findByIdForUpdate(RunId id) {
        return jdbc.sql(SELECT_RUNS + "WHERE id = ? FOR UPDATE").param(id.value()).query(this::map).optional();
    }

    @Override
    public Optional<AiRun> findActiveByKey(String idempotencyKey) {
        return jdbc.sql(SELECT_RUNS + "WHERE idempotency_key = ? AND state IN ('QUEUED', 'RUNNING', 'WAITING_RETRY')")
                .param(idempotencyKey).query(this::map).optional();
    }

    @Override
    public Optional<AiRun> findLatestByKey(String idempotencyKey) {
        return jdbc.sql(SELECT_RUNS + "WHERE idempotency_key = ? ORDER BY requested_at DESC, id LIMIT 1")
                .param(idempotencyKey).query(this::map).optional();
    }

    @Override
    public java.util.Map<String, AiRun> findLatestByKeys(java.util.Collection<String> idempotencyKeys) {
        java.util.Map<String, AiRun> latest = new java.util.LinkedHashMap<>();
        if (idempotencyKeys.isEmpty()) {
            return latest;
        }
        jdbc.sql("SELECT DISTINCT ON (idempotency_key) " + COLUMNS
                + " FROM ai_runs WHERE idempotency_key IN (:keys) ORDER BY idempotency_key, requested_at DESC, id")
                .param("keys", List.copyOf(idempotencyKeys)).query(this::map).list()
                .forEach(run -> latest.put(run.idempotencyKey(), run));
        return latest;
    }

    @Override
    public List<AiRun> findRunning() {
        return jdbc.sql(SELECT_RUNS + "WHERE state = 'RUNNING'").query(this::map).list();
    }

    @Override
    public List<AiRun> findAwaitingSlot(Instant now) {
        return jdbc.sql(SELECT_RUNS + """
                WHERE NOT cancel_requested AND (state = 'QUEUED'
                       OR state = 'WAITING_RETRY' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                ORDER BY requested_at, id""").param(Timestamp.from(now)).query(this::map).list();
    }

    @Override
    public List<AiRun> findRecent(int limit) {
        return jdbc.sql(SELECT_RUNS + "ORDER BY requested_at DESC, id LIMIT ?").param(limit).query(this::map).list();
    }

    @Override
    public List<AiRun> findLatestOutcomePerModel() {
        return jdbc.sql("SELECT DISTINCT ON (provider, model) " + COLUMNS
                + " FROM ai_runs WHERE state IN ('SUCCEEDED', 'FAILED') AND finished_at IS NOT NULL"
                + " ORDER BY provider, model, finished_at DESC, id").query(this::map).list();
    }

    @Override
    public Optional<Instant> lastSucceededAt() {
        return jdbc.sql("SELECT max(finished_at) FROM ai_runs WHERE state = 'SUCCEEDED'")
                .query((rs, row) -> Optional.ofNullable(instant(rs, 1))).single();
    }

    @Override
    public List<RunStep> steps(RunId id) {
        return jdbc.sql("""
                SELECT seq, attempt, kind, name, outcome, input_tokens, output_tokens, cache_read_tokens,
                       cache_write_tokens, cost, currency, at
                  FROM ai_run_steps WHERE run_id = ? ORDER BY seq""").param(id.value())
                .query((rs, row) -> new RunStep(rs.getInt("seq"), rs.getInt("attempt"),
                        StepKind.valueOf(rs.getString("kind")), rs.getString("name"), rs.getString("outcome"),
                        new TokenUsage(rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                                rs.getLong("cache_read_tokens"), rs.getLong("cache_write_tokens")),
                        rs.getBigDecimal("cost") == null ? null
                                : new Money(rs.getBigDecimal("cost"), Currency.getInstance(rs.getString("currency"))),
                        instant(rs, "at")))
                .list();
    }

    @Override
    public CostSample recentSucceededCost(Workload workload, String model, Currency currency, int limit) {
        return jdbc.sql("""
                SELECT avg(cost) AS average, count(*) AS runs FROM (
                    SELECT cost FROM ai_runs
                     WHERE workload = ? AND model = ? AND currency = ? AND state = 'SUCCEEDED'
                     ORDER BY finished_at DESC LIMIT ?) recent""")
                .params(workload.name(), model, currency.getCurrencyCode(), limit)
                .query((rs, row) -> {
                    BigDecimal average = rs.getBigDecimal("average");
                    int runs = rs.getInt("runs");
                    return new CostSample(runs == 0 || average == null ? null : new Money(average, currency), runs);
                }).single();
    }

    @Override
    public Money costSince(Instant since, String provider, Set<Workload> workloads, Currency currency) {
        if (workloads.isEmpty()) {
            return Money.zero(currency);
        }
        BigDecimal total = jdbc.sql("""
                SELECT coalesce(sum(s.cost), 0) FROM ai_run_steps s JOIN ai_runs r ON r.id = s.run_id
                 WHERE s.kind = 'MODEL_CALL' AND s.at >= :since AND s.currency = :currency
                   AND r.provider = :provider AND r.workload IN (:workloads)""")
                .param("since", Timestamp.from(since)).param("currency", currency.getCurrencyCode())
                .param("provider", provider).param("workloads", workloads.stream().map(Workload::name).toList())
                .query(BigDecimal.class).single();
        return new Money(total, currency);
    }

    @Override
    public void save(AiRun run) {
        Map<String, Object> values = stateValues(run);
        if (run.isNew()) {
            values.putAll(requestValues(run));
            jdbc.sql("""
                    INSERT INTO ai_runs (id, workload, idempotency_key, retry_of, requested_by, requested_at,
                        provider, model, parameters, sources, budget_max_duration_ms, budget_max_cost, currency,
                        budget_max_model_calls, budget_max_tool_calls, budget_max_output_tokens, budget_max_attempts,
                        state, attempt, attempt_started_at, next_attempt_at, finished_at, cancel_requested,
                        cancel_requested_by, model_calls, tool_calls, denied_tool_calls, attempt_model_calls,
                        attempt_tool_calls, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost,
                        output, failure_category, failure_code, failure_message, failure_at, step_count,
                        budget_waits, slot_offered_at, version)
                    VALUES (:id, :workload, :key, :retryOf, :requestedBy, :requestedAt, :provider, :model,
                        CAST(:parameters AS jsonb), CAST(:sources AS jsonb), :maxDurationMs, :maxCost, :currency,
                        :maxModelCalls, :maxToolCalls, :maxOutputTokens, :maxAttempts, :state, :attempt,
                        :attemptStartedAt, :nextAttemptAt, :finishedAt, :cancelRequested, :cancelRequestedBy,
                        :modelCalls, :toolCalls, :deniedToolCalls, :attemptModelCalls, :attemptToolCalls,
                        :inputTokens, :outputTokens, :cacheReadTokens, :cacheWriteTokens, :cost, :output,
                        :failureCategory, :failureCode, :failureMessage, :failureAt, :stepCount, :budgetWaits,
                        :slotOfferedAt, 0)""")
                    .params(values).update();
        } else {
            values.put("version", run.version());
            int updated = jdbc.sql("""
                    UPDATE ai_runs SET state = :state, attempt = :attempt, attempt_started_at = :attemptStartedAt,
                           next_attempt_at = :nextAttemptAt, finished_at = :finishedAt,
                           cancel_requested = :cancelRequested, cancel_requested_by = :cancelRequestedBy,
                           model_calls = :modelCalls, tool_calls = :toolCalls, denied_tool_calls = :deniedToolCalls,
                           attempt_model_calls = :attemptModelCalls, attempt_tool_calls = :attemptToolCalls,
                           input_tokens = :inputTokens, output_tokens = :outputTokens,
                           cache_read_tokens = :cacheReadTokens, cache_write_tokens = :cacheWriteTokens,
                           cost = :cost, output = :output, failure_category = :failureCategory,
                           failure_code = :failureCode, failure_message = :failureMessage, failure_at = :failureAt,
                           step_count = :stepCount, budget_waits = :budgetWaits,
                           slot_offered_at = :slotOfferedAt, version = version + 1
                     WHERE id = :id AND version = :version""").params(values).update();
            if (updated == 0) {
                long stored = jdbc.sql("SELECT version FROM ai_runs WHERE id = ?").param(run.id().value())
                        .query(Long.class).optional().orElse(-1L);
                throw new StaleVersionException("aiRun", run.id().toString(), run.version(), stored);
            }
        }
        for (RunStep step : run.pendingSteps()) {
            insertStep(run.id(), step);
        }
        run.persisted();
    }

    private void insertStep(RunId run, RunStep step) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runId", run.value());
        values.put("seq", step.seq());
        values.put("attempt", step.attempt());
        values.put("kind", step.kind().name());
        values.put("name", step.name());
        values.put("outcome", step.outcome());
        values.put("inputTokens", step.tokens().inputTokens());
        values.put("outputTokens", step.tokens().outputTokens());
        values.put("cacheReadTokens", step.tokens().cacheReadTokens());
        values.put("cacheWriteTokens", step.tokens().cacheWriteTokens());
        values.put("cost", step.cost() == null ? null : step.cost().amount());
        values.put("currency", step.cost() == null ? null : step.cost().currency().getCurrencyCode());
        values.put("at", Timestamp.from(step.at()));
        jdbc.sql("""
                INSERT INTO ai_run_steps (run_id, seq, attempt, kind, name, outcome, input_tokens, output_tokens,
                    cache_read_tokens, cache_write_tokens, cost, currency, at)
                VALUES (:runId, :seq, :attempt, :kind, :name, :outcome, :inputTokens, :outputTokens,
                    :cacheReadTokens, :cacheWriteTokens, :cost, :currency, :at)""").params(values).update();
    }

    private Map<String, Object> requestValues(AiRun run) {
        Map<String, Object> values = new LinkedHashMap<>();
        RunBudget budget = run.budget();
        values.put("workload", run.workload().name());
        values.put("key", run.idempotencyKey());
        values.put("retryOf", run.retryOf().map(RunId::value).orElse(null));
        values.put("requestedBy", run.requestedBy().value());
        values.put("requestedAt", Timestamp.from(run.requestedAt()));
        values.put("provider", run.model().provider());
        values.put("model", run.model().model());
        values.put("parameters", json.writeValueAsString(run.parameters()));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (SourceRef source : run.sources()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", source.kind());
            entry.put("id", source.id());
            entry.put("version", source.version());
            sources.add(entry);
        }
        values.put("sources", json.writeValueAsString(sources));
        values.put("maxDurationMs", budget.maxDuration().toMillis());
        values.put("maxCost", budget.maxCost().amount());
        values.put("currency", budget.maxCost().currency().getCurrencyCode());
        values.put("maxModelCalls", budget.maxModelCalls());
        values.put("maxToolCalls", budget.maxToolCalls());
        values.put("maxOutputTokens", budget.maxOutputTokens());
        values.put("maxAttempts", budget.maxAttempts());
        return values;
    }

    private static Map<String, Object> stateValues(AiRun run) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", run.id().value());
        values.put("state", run.state().name());
        values.put("attempt", run.attempt());
        values.put("attemptStartedAt", run.attemptStartedAt().map(Timestamp::from).orElse(null));
        values.put("nextAttemptAt", run.nextAttemptAt().map(Timestamp::from).orElse(null));
        values.put("finishedAt", run.finishedAt().map(Timestamp::from).orElse(null));
        values.put("cancelRequested", run.cancelRequested());
        values.put("cancelRequestedBy", run.cancelRequestedBy().map(MemberId::value).orElse(null));
        values.put("modelCalls", run.modelCalls());
        values.put("toolCalls", run.toolCalls());
        values.put("deniedToolCalls", run.deniedToolCalls());
        values.put("attemptModelCalls", run.attemptModelCalls());
        values.put("attemptToolCalls", run.attemptToolCalls());
        values.put("inputTokens", run.tokens().inputTokens());
        values.put("outputTokens", run.tokens().outputTokens());
        values.put("cacheReadTokens", run.tokens().cacheReadTokens());
        values.put("cacheWriteTokens", run.tokens().cacheWriteTokens());
        values.put("cost", run.cost().amount());
        values.put("output", run.output().orElse(null));
        RunFailure failure = run.failure().orElse(null);
        values.put("failureCategory", failure == null ? null : failure.category().name());
        values.put("failureCode", failure == null ? null : failure.code());
        values.put("failureMessage", failure == null ? null : failure.message());
        values.put("failureAt", failure == null ? null : Timestamp.from(failure.at()));
        values.put("stepCount", run.stepCount());
        values.put("budgetWaits", run.budgetWaits());
        values.put("slotOfferedAt", run.slotOfferedAt().map(Timestamp::from).orElse(null));
        return values;
    }

    private AiRun map(ResultSet rs, int row) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        RunBudget budget = new RunBudget(Duration.ofMillis(rs.getLong("budget_max_duration_ms")),
                new Money(rs.getBigDecimal("budget_max_cost"), currency), rs.getInt("budget_max_model_calls"),
                rs.getInt("budget_max_tool_calls"), rs.getInt("budget_max_output_tokens"),
                rs.getInt("budget_max_attempts"));
        UUID retryOf = rs.getObject("retry_of", UUID.class);
        UUID cancelledBy = rs.getObject("cancel_requested_by", UUID.class);
        String failureCategory = rs.getString("failure_category");
        RunFailure failure = failureCategory == null ? null
                : new RunFailure(FailureCategory.valueOf(failureCategory), rs.getString("failure_code"),
                        rs.getString("failure_message"), instant(rs, "failure_at"));
        AiRun.Snapshot snapshot = new AiRun.Snapshot(RunState.valueOf(rs.getString("state")), rs.getInt("attempt"),
                instant(rs, "attempt_started_at"), instant(rs, "next_attempt_at"), instant(rs, "finished_at"),
                rs.getBoolean("cancel_requested"), cancelledBy == null ? null : new MemberId(cancelledBy),
                rs.getInt("model_calls"), rs.getInt("tool_calls"), rs.getInt("denied_tool_calls"),
                rs.getInt("attempt_model_calls"), rs.getInt("attempt_tool_calls"),
                new TokenUsage(rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                        rs.getLong("cache_read_tokens"), rs.getLong("cache_write_tokens")),
                new Money(rs.getBigDecimal("cost"), currency), rs.getString("output"), failure, rs.getLong("version"),
                rs.getInt("step_count"), rs.getInt("budget_waits"), instant(rs, "slot_offered_at"));
        List<SourceRef> sources = new ArrayList<>();
        for (Map<String, Object> source : json.readValue(rs.getString("sources_json"), SOURCES)) {
            sources.add(new SourceRef(String.valueOf(source.get("kind")), String.valueOf(source.get("id")),
                    ((Number) source.get("version")).longValue()));
        }
        return AiRun.rehydrate(new RunId(rs.getObject("id", UUID.class)), Workload.valueOf(rs.getString("workload")),
                rs.getString("idempotency_key"), retryOf == null ? null : new RunId(retryOf),
                new MemberId(rs.getObject("requested_by", UUID.class)), instant(rs, "requested_at"),
                new ModelSelection(rs.getString("provider"), rs.getString("model")), budget, sources,
                json.readValue(rs.getString("parameters_json"), PARAMETERS), snapshot);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
