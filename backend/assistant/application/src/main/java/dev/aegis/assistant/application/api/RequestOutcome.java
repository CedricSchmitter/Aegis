package dev.aegis.assistant.application.api;

/** Result of a request that must not fail its caller when AI is unavailable. */
public sealed interface RequestOutcome permits RequestOutcome.Requested, RequestOutcome.Refused {
    /** The run (new, or the existing one for the same unit of work). */
    record Requested(RunView run) implements RequestOutcome {
    }

    /**
     * Nothing was queued. {@code code} is {@code ai.notConfigured}, {@code ai.workload.unavailable},
     * {@code ai.price.unknown} or {@code auth.forbidden}.
     */
    record Refused(String code) implements RequestOutcome {
    }
}
