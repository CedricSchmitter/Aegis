package dev.aegis.assistant.application.ports;

import dev.aegis.assistant.domain.RunId;
import java.time.Instant;

/**
 * Durable execution of runs. Scheduling takes part in the caller's database transaction, so a run and its job are
 * created atomically; scheduling an already scheduled run is a no-op.
 */
public interface RunScheduler {
    void schedule(RunId id, Instant at);

    /**
     * Moves the run's job forward to {@code at} if it is scheduled later, as part of the caller's transaction. A job
     * that is executing right now is left alone; the execution reports back through {@code RunExecution#wakeIfOffered}
     * once its next time is stored.
     *
     * @return whether the run has a job that will execute it: moved, already due or executing; false without a job
     */
    boolean wake(RunId id, Instant at);
}
