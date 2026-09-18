package dev.aegis.assistant.application.api;

import java.math.BigDecimal;

/**
 * Configured profile of a workload. {@code available} means a run can be requested now: a handler exists, the
 * provider has credentials and the model has a price. The connection check has no model of its own.
 */
public record WorkloadView(String workload, String provider, String model, boolean providerConfigured,
        boolean handlerAvailable, boolean available, BigDecimal maxCost, long maxDurationSeconds, int maxModelCalls,
        int maxToolCalls, int maxOutputTokens, int maxAttempts, int maxConcurrent) {
}
