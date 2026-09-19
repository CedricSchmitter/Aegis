package dev.aegis.assistant.application.api;

import dev.aegis.shared.Actor;

/**
 * Entry point for other contexts and inbound adapters that need AI work done (classification, review, chat). The
 * run executes durably in the background; the request itself never waits for a model and fails with
 * {@code ai.notConfigured} or {@code ai.workload.unavailable} instead of queuing work that cannot run.
 */
public interface RequestAiRun {
    /**
     * @throws dev.aegis.shared.InvalidInputException {@code ai.run.keyConflict} when the idempotency key already
     *         belongs to a different unit of work (other workload, sources or parameters)
     */
    RunView request(Actor actor, RunRequest request);

    /**
     * Like {@link #request}, but reports an unavailable configuration or a requester who is no longer a member as
     * {@link RequestOutcome.Refused} instead of throwing, so a caller can record it inside its own transaction.
     */
    RequestOutcome tryRequest(Actor actor, RunRequest request);
}
