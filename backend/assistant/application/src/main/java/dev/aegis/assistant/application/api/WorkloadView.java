package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Configured profile of a workload. {@code available} means a run can be requested now: a handler exists, the
 * provider has credentials and the model has a price. {@code family} is the household's model family for new runs
 * with {@code choiceVersion} for optimistic concurrency and who changed it last (none while the configured default
 * applies). The connection check has no model or family of its own.
 *
 * <p>{@code maxCost} is the session limit (cost budget of one new run) and {@code dailyCostLimit} the daily limit
 * that applies to the workload on the shown provider, with {@code spentToday} under that limit. {@code ownLimits}
 * tells whether the household set both limits for this workload ({@code limitsVersion}, who changed them last);
 * otherwise the configured defaults apply and the daily limit is shared with the other workloads without own limits.
 */
public record WorkloadView(String workload, String provider, String model, String family, long choiceVersion,
        UUID choiceChangedBy, Instant choiceChangedAt, boolean providerConfigured, boolean handlerAvailable,
        boolean available, BigDecimal maxCost, long maxDurationSeconds, int maxModelCalls, int maxToolCalls,
        int maxOutputTokens, int maxAttempts, int maxConcurrent, BigDecimal dailyCostLimit, BigDecimal spentToday,
        boolean ownLimits, boolean limitsSelectable, long limitsVersion, UUID limitsChangedBy,
        Instant limitsChangedAt) {
}
