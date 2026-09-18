package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Household AI configuration and today's spending. {@code configured} is true when any provider has credentials;
 * {@code memberAllowed} is retained for API compatibility and matches {@code configured} for every active household
 * member. Membership is checked before returning this view.
 */
public record AiOverview(boolean configured, boolean memberAllowed, BigDecimal dailyCostLimit, BigDecimal spentToday,
        String currency, int maxConcurrentRuns, List<WorkloadView> workloads) {
}
