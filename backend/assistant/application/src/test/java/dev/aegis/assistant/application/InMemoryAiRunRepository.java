package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.RunStep;
import dev.aegis.assistant.domain.StepKind;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Behaves like the PostgreSQL store: every load returns a fresh copy, updates are version-checked and at most one
 * active run per idempotency key can exist.
 */
class InMemoryAiRunRepository implements AiRunRepository {
    private final Map<RunId, AiRun> runs = new LinkedHashMap<>();
    private final Map<RunId, List<RunStep>> steps = new LinkedHashMap<>();

    @Override
    public synchronized Optional<AiRun> findById(RunId id) {
        return Optional.ofNullable(runs.get(id)).map(InMemoryAiRunRepository::copy);
    }

    @Override
    public Optional<AiRun> findByIdForUpdate(RunId id) {
        return findById(id);
    }

    @Override
    public synchronized Optional<AiRun> findActiveByKey(String key) {
        return runs.values().stream().filter(run -> run.idempotencyKey().equals(key) && run.state().active())
                .findFirst().map(InMemoryAiRunRepository::copy);
    }

    @Override
    public synchronized Optional<AiRun> findLatestByKey(String key) {
        return runs.values().stream().filter(run -> run.idempotencyKey().equals(key))
                .max(Comparator.comparing(AiRun::requestedAt)).map(InMemoryAiRunRepository::copy);
    }

    @Override
    public synchronized java.util.Map<String, AiRun> findLatestByKeys(java.util.Collection<String> keys) {
        java.util.Map<String, AiRun> latest = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            findLatestByKey(key).ifPresent(run -> latest.put(key, run));
        }
        return latest;
    }

    @Override
    public synchronized List<AiRun> findRunning() {
        return runs.values().stream().filter(run -> run.state() == RunState.RUNNING)
                .map(InMemoryAiRunRepository::copy).toList();
    }

    @Override
    public synchronized List<AiRun> findAwaitingSlot(Instant now) {
        return runs.values().stream().filter(run -> run.awaitsSlot(now))
                .sorted(Comparator.comparing(AiRun::requestedAt).thenComparing(run -> run.id().value()))
                .map(InMemoryAiRunRepository::copy).toList();
    }

    @Override
    public synchronized List<AiRun> findRecent(int limit) {
        return runs.values().stream().sorted(Comparator.comparing(AiRun::requestedAt).reversed()).limit(limit)
                .map(InMemoryAiRunRepository::copy).toList();
    }

    @Override
    public synchronized List<AiRun> findLatestOutcomePerModel() {
        Map<ModelSelection, AiRun> latest = new LinkedHashMap<>();
        runs.values().stream()
                .filter(run -> run.state() == RunState.SUCCEEDED || run.state() == RunState.FAILED)
                .sorted(Comparator.comparing(run -> run.finishedAt().get()))
                .forEach(run -> latest.put(run.model(), run));
        return latest.values().stream().map(InMemoryAiRunRepository::copy).toList();
    }

    @Override
    public synchronized Optional<Instant> lastSucceededAt() {
        return runs.values().stream().filter(run -> run.state() == RunState.SUCCEEDED)
                .map(run -> run.finishedAt().get()).max(Comparator.naturalOrder());
    }

    @Override
    public synchronized List<RunStep> steps(RunId id) {
        return List.copyOf(steps.getOrDefault(id, List.of()));
    }

    @Override
    public synchronized CostSample recentSucceededCost(Workload workload, String model, Currency currency,
            int limit) {
        List<AiRun> succeeded = runs.values().stream().filter(run -> run.workload() == workload
                        && run.model().model().equals(model) && run.state() == RunState.SUCCEEDED)
                .sorted(Comparator.comparing((AiRun run) -> run.finishedAt().orElse(Instant.MIN)).reversed())
                .limit(limit).toList();
        if (succeeded.isEmpty()) {
            return new CostSample(null, 0);
        }
        java.math.BigDecimal total = succeeded.stream().map(run -> run.cost().amount())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return new CostSample(new Money(total.divide(java.math.BigDecimal.valueOf(succeeded.size()),
                Money.SCALE, java.math.RoundingMode.HALF_UP), currency), succeeded.size());
    }

    @Override
    public synchronized Money costSince(Instant since, String provider, Currency currency) {
        Money total = Money.zero(currency);
        for (Map.Entry<RunId, List<RunStep>> entry : steps.entrySet()) {
            if (!runs.get(entry.getKey()).model().provider().equals(provider)) {
                continue;
            }
            for (RunStep step : entry.getValue()) {
                if (step.kind() == StepKind.MODEL_CALL && !step.at().isBefore(since) && step.cost() != null) {
                    total = total.plus(step.cost());
                }
            }
        }
        return total;
    }

    @Override
    public synchronized void save(AiRun run) {
        if (run.isNew()) {
            if (runs.containsKey(run.id())) {
                throw new IllegalStateException("duplicate run id");
            }
            steps.put(run.id(), new ArrayList<>());
        } else {
            AiRun stored = runs.get(run.id());
            if (stored.version() != run.version()) {
                throw new StaleVersionException("aiRun", run.id().toString(), run.version(), stored.version());
            }
        }
        if (run.state().active() && runs.values().stream().anyMatch(other -> !other.id().equals(run.id())
                && other.idempotencyKey().equals(run.idempotencyKey()) && other.state().active())) {
            throw new IllegalStateException("unique active key violated");
        }
        steps.get(run.id()).addAll(run.pendingSteps());
        run.persisted();
        runs.put(run.id(), copy(run));
    }

    /** The stored run as it is now, for assertions. */
    synchronized AiRun stored(RunId id) {
        return copy(runs.get(id));
    }

    synchronized int size() {
        return runs.size();
    }

    static AiRun copy(AiRun run) {
        AiRun.Snapshot snapshot = new AiRun.Snapshot(run.state(), run.attempt(), run.attemptStartedAt().orElse(null),
                run.nextAttemptAt().orElse(null), run.finishedAt().orElse(null), run.cancelRequested(),
                run.cancelRequestedBy().orElse(null), run.modelCalls(), run.toolCalls(), run.deniedToolCalls(),
                run.attemptModelCalls(), run.attemptToolCalls(), run.tokens(), run.cost(), run.output().orElse(null),
                run.failure().orElse(null), run.version(), run.stepCount(), run.budgetWaits(),
                run.slotOfferedAt().orElse(null));
        return AiRun.rehydrate(run.id(), run.workload(), run.idempotencyKey(), run.retryOf().orElse(null),
                run.requestedBy(), run.requestedAt(), run.model(), run.budget(), run.sources(), run.parameters(),
                snapshot);
    }
}
