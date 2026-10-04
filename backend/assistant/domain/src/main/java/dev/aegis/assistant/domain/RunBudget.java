package dev.aegis.assistant.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Hard limits of one run. {@code maxCost} covers all attempts together; duration, model calls and tool calls apply
 * per attempt; {@code maxOutputTokens} caps every single model call.
 */
public record RunBudget(Duration maxDuration, Money maxCost, int maxModelCalls, int maxToolCalls,
        int maxOutputTokens, int maxAttempts) {
    public RunBudget {
        Objects.requireNonNull(maxDuration, "max duration");
        Objects.requireNonNull(maxCost, "max cost");
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("max duration must be positive");
        }
        if (!maxCost.isPositive()) {
            throw new IllegalArgumentException("max cost must be positive");
        }
        if (maxModelCalls < 1 || maxToolCalls < 0 || maxOutputTokens < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("model calls, output tokens and attempts must be positive");
        }
    }

    /** The same limits with another cost budget. */
    public RunBudget withMaxCost(Money cost) {
        return new RunBudget(maxDuration, cost, maxModelCalls, maxToolCalls, maxOutputTokens, maxAttempts);
    }
}
