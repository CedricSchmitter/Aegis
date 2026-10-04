package dev.aegis.assistant.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persistence of runs and their audit steps. {@link #save} checks the version of an existing run. */
public interface AiRunRepository {
    Optional<AiRun> findById(RunId id);

    /**
     * Loads the run and locks it until the surrounding transaction ends, so that execution steps and member commands
     * on the same run are serialized instead of failing each other with version conflicts.
     */
    Optional<AiRun> findByIdForUpdate(RunId id);

    /** The queued, running or waiting run with this key; at most one exists. */
    Optional<AiRun> findActiveByKey(String idempotencyKey);

    /** The most recently requested run with this key, in any state. */
    Optional<AiRun> findLatestByKey(String idempotencyKey);

    /** The most recently requested run per key; keys without a run are absent. */
    java.util.Map<String, AiRun> findLatestByKeys(java.util.Collection<String> idempotencyKeys);

    /** Runs in state {@code RUNNING}, for concurrency accounting. */
    List<AiRun> findRunning();

    /**
     * Runs that are due at {@code now} and could start an attempt if a slot were free, as decided by
     * {@link AiRun#awaitsSlot}: queued runs and waiting runs whose next attempt is due. Oldest request first.
     */
    List<AiRun> findAwaitingSlot(Instant now);

    /** Most recently requested runs first. */
    List<AiRun> findRecent(int limit);

    /** For every provider and model, its most recently finished run that succeeded or failed. */
    List<AiRun> findLatestOutcomePerModel();

    Optional<Instant> lastSucceededAt();

    List<RunStep> steps(RunId id);

    /** Mean cost of {@code runs} succeeded runs; {@code average} is {@code null} without any. */
    record CostSample(Money average, int runs) {
    }

    /**
     * Mean cost of the most recently finished succeeded runs of {@code workload} on {@code model}, at most
     * {@code limit} of them, in {@code currency}.
     */
    CostSample recentSucceededCost(Workload workload, String model, Currency currency, int limit);

    /**
     * Cost of all model calls recorded at or after {@code since} by runs of {@code workloads} on {@code provider};
     * zero without workloads.
     */
    Money costSince(Instant since, String provider, Set<Workload> workloads, Currency currency);

    /**
     * Inserts a new run or updates an existing one if its stored version still matches, then appends the pending
     * steps.
     *
     * @throws dev.aegis.shared.StaleVersionException when another transaction changed the run meanwhile
     */
    void save(AiRun run);
}
