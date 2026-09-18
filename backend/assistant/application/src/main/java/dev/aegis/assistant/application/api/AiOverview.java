package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.util.List;

/** Household AI configuration and today's spending; {@code configured} is true when any provider has credentials. */
public record AiOverview(boolean configured, BigDecimal dailyCostLimit, BigDecimal spentToday, String currency,
        int maxConcurrentRuns, List<WorkloadView> workloads) {
}
