package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a run of a workload is expected to cost the asking member, and how much of the household's daily AI budget is
 * left. {@code refusal} is the code a request of this member would be refused with now
 * ({@code ai.notConfigured}, {@code ai.provider.memberNotAllowed}, ...), otherwise {@code null}. Provider and model are
 * the ones a run would use (or the configured ones while refused). {@code averageCost} is the mean cost of the last
 * {@code samples} succeeded runs of the workload on that model, {@code null} without any; {@code runBudget} is the most
 * one run may cost. The daily limit and today's spending belong to that provider; {@code nextBudgetDay} is when the
 * limit renews. Amounts are estimates from list prices, not charges.
 */
public record WorkloadEstimate(String workload, String refusal, String provider, String model, BigDecimal averageCost,
        int samples, BigDecimal runBudget, BigDecimal dailyCostLimit, BigDecimal spentToday, String currency,
        Instant nextBudgetDay) {
}
