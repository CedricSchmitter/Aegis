package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Household AI configuration and today's spending. {@code configured} is true when any provider has credentials;
 * {@code memberAllowed} tells whether the asking member may use at least one configured model (the owner's personal
 * subscription serves only its owner). {@code limitProvider} is the provider whose shared daily limit and spending
 * today are shown: the one this member's runs use now, since each provider can have its own daily limit. The shared
 * limit covers the workloads without own limits; {@code spentToday} counts only their calls. {@code models}
 * are the families the household can choose per workload. Membership is checked before returning this view.
 */
public record AiOverview(boolean configured, boolean memberAllowed, String limitProvider, BigDecimal dailyCostLimit,
        BigDecimal spentToday, String currency, int maxConcurrentRuns, List<WorkloadView> workloads,
        List<ModelOptionView> models) {
}
