package dev.aegis.assistant.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Amount with an explicit currency, kept at micro-unit precision (six decimals). Provider prices are quoted in USD;
 * mixing currencies is refused rather than converted.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {
    public static final int SCALE = 6;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currency));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean exceeds(Money other) {
        return compareTo(other) > 0;
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
