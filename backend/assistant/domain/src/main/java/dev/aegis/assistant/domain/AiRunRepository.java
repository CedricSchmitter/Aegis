package dev.aegis.assistant.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

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

    /** Runs in state {@code RUNNING}, for concurrency accounting. */
    List<AiRun> findRunning();

    /** Most recently requested runs first. */
    List<AiRun> findRecent(int limit);

    /** Most recently finished run, if any. */
    Optional<AiRun> findLatestFinished();

    Optional<Instant> lastSucceededAt();

    List<RunStep> steps(RunId id);

    /** Cost of all model calls recorded at or after {@code since}, across runs. */
    Money costSince(Instant since, Currency currency);

    /**
     * Inserts a new run or updates an existing one if its stored version still matches, then appends the pending
     * steps.
     *
     * @throws dev.aegis.shared.StaleVersionException when another transaction changed the run meanwhile
     */
    void save(AiRun run);
}
