package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.Money;

/**
 * Limits of one provider that replace the household defaults for its runs. {@code dailyCostLimit} caps the cost of
 * that provider's model calls per household day; {@code maxRunCost} replaces the cost budget of every run on that
 * provider. Either may be null to keep the default. Used for the owner's subscription, whose own usage limits apply
 * instead of per-token billing.
 */
public record ProviderLimits(Money dailyCostLimit, Money maxRunCost) {
    public ProviderLimits {
        if (dailyCostLimit != null && !dailyCostLimit.isPositive()
                || maxRunCost != null && !maxRunCost.isPositive()) {
            throw new IllegalArgumentException("provider limits must be positive");
        }
    }
}
