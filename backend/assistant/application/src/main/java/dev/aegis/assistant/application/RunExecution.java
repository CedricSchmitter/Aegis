package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.TokenUsage;
import java.time.Duration;
import java.time.Instant;

/**
 * Job-facing steps of a run. Each call is one short transaction; model calls and tool invocations happen between
 * calls, so no database transaction waits on a provider. Every step reloads the run and lets the aggregate decide,
 * which keeps restarts, revived jobs and concurrent cancellations consistent.
 */
public interface RunExecution {

    sealed interface Begin permits Proceed, Later, Finished {
    }

    /** A new attempt started. */
    record Proceed(RunContext context) implements Begin {
    }

    /** Not due yet or no free slot; execute again at {@code at}. */
    record Later(Instant at) implements Begin {
    }

    /** Nothing to do: the run ended, was cancelled or does not exist ({@code state} null). */
    record Finished(RunState state) implements Begin {
    }

    Begin begin(RunId id);

    AiRun.Checkpoint beforeModelCall(RunId id, int attempt, AiRun.CallQuote quote);

    AiRun.Checkpoint afterModelCall(RunId id, int attempt, String modelId, String outcome, TokenUsage usage,
            Money cost);

    AiRun.Checkpoint beforeToolCall(RunId id, int attempt, String toolName, boolean granted);

    /**
     * Applies the validated result exactly once: checks cancellation and source versions, claims the effect key,
     * applies the effect and marks the run succeeded in one transaction.
     */
    AiRun.Checkpoint complete(RunId id, int attempt, ValidatedOutput output);

    AiRun.Checkpoint fail(RunId id, int attempt, FailureCategory category, String code, String message,
            Duration retryAfter);
}
