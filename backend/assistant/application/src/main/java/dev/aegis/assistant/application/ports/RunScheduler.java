package dev.aegis.assistant.application.ports;

import dev.aegis.assistant.domain.RunId;
import java.time.Instant;

/**
 * Durable execution of runs. Scheduling takes part in the caller's database transaction, so a run and its job are
 * created atomically; scheduling an already scheduled run is a no-op.
 */
public interface RunScheduler {
    void schedule(RunId id, Instant at);
}
