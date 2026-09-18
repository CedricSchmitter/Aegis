package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.TokenUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Provider list prices of one model per million tokens, taken from configuration. */
public record ModelPrice(Money inputPerMillion, Money outputPerMillion, Money cacheReadPerMillion,
        Money cacheWritePerMillion) {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public ModelPrice {
        Objects.requireNonNull(inputPerMillion, "input price");
        Objects.requireNonNull(outputPerMillion, "output price");
        cacheReadPerMillion = cacheReadPerMillion == null ? inputPerMillion : cacheReadPerMillion;
        cacheWritePerMillion = cacheWritePerMillion == null ? inputPerMillion : cacheWritePerMillion;
    }

    /** Cost of reported usage; rounded up to the micro-unit so that budgets never under-count. */
    public Money cost(TokenUsage usage) {
        BigDecimal total = inputPerMillion.amount().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(outputPerMillion.amount().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .add(cacheReadPerMillion.amount().multiply(BigDecimal.valueOf(usage.cacheReadTokens())))
                .add(cacheWritePerMillion.amount().multiply(BigDecimal.valueOf(usage.cacheWriteTokens())));
        return new Money(total.divide(MILLION, Money.SCALE, RoundingMode.CEILING), inputPerMillion.currency());
    }

    public Money inputCost(long tokens) {
        return new Money(inputPerMillion.amount().multiply(BigDecimal.valueOf(tokens))
                .divide(MILLION, Money.SCALE, RoundingMode.CEILING), inputPerMillion.currency());
    }
}
