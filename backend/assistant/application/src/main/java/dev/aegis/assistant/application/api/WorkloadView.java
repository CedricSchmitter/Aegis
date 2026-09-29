package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Configured profile of a workload. {@code available} means a run can be requested now: a handler exists, the
 * provider has credentials and the model has a price. {@code family} is the household's model family for new runs
 * with {@code choiceVersion} for optimistic concurrency and who changed it last (none while the configured default
 * applies). The connection check has no model or family of its own.
 */
public record WorkloadView(String workload, String provider, String model, String family, long choiceVersion,
        UUID choiceChangedBy, Instant choiceChangedAt, boolean providerConfigured, boolean handlerAvailable,
        boolean available, BigDecimal maxCost, long maxDurationSeconds, int maxModelCalls, int maxToolCalls,
        int maxOutputTokens, int maxAttempts, int maxConcurrent) {
}
