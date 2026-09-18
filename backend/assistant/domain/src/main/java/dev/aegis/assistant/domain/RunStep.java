package dev.aegis.assistant.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Append-only audit step. {@code name} is a model id or tool name, {@code outcome} a stable code; {@code cost} is set
 * for model calls only. Steps never carry prompts, document text or tool arguments.
 */
public record RunStep(int seq, int attempt, StepKind kind, String name, String outcome, TokenUsage tokens,
        Money cost, Instant at) {
    public RunStep {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(at, "at");
        tokens = tokens == null ? TokenUsage.NONE : tokens;
    }
}
