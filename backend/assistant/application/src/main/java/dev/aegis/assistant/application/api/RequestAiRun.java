package dev.aegis.assistant.application.api;

import dev.aegis.shared.Actor;

/**
 * Entry point for other contexts and inbound adapters that need AI work done (classification, review, chat). The
 * run executes durably in the background; the request itself never waits for a model and fails with
 * {@code ai.notConfigured} or {@code ai.workload.unavailable} instead of queuing work that cannot run.
 */
public interface RequestAiRun {
    RunView request(Actor actor, RunRequest request);
}
