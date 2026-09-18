package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Household AI configuration and today's spending. {@code configured} is true when any provider has credentials;
 * {@code memberAllowed} tells whether the asking member may use at least one configured model (a personal
 * subscription, for example, is limited to its owner).
 */
public record AiOverview(boolean configured, boolean memberAllowed, BigDecimal dailyCostLimit, BigDecimal spentToday,
        String currency, int maxConcurrentRuns, List<WorkloadView> workloads) {
}
