package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Visible state of a run. Codes (workload, state, failure category and code, step kinds) are stable English values
 * that the UI localizes. {@code output} is the validated result as JSON. {@code steps} is empty in list views.
 * {@code attempt} counts the attempts against {@code maxAttempts}; attempts that only waited for the next budget day
 * are not counted, while the steps number every attempt.
 */
public record RunView(UUID id, String workload, String state, UUID retryOf, UUID requestedBy, Instant requestedAt,
        String provider, String model, int attempt, int maxAttempts, boolean cancelRequested, int modelCalls,
        int toolCalls, int deniedToolCalls, long inputTokens, long outputTokens, BigDecimal cost, BigDecimal maxCost,
        String currency, String output, String failureCategory, String failureCode, String failureMessage,
        Instant nextAttemptAt, Instant finishedAt, List<SourceVersion> sources, List<StepView> steps, long version) {
}
