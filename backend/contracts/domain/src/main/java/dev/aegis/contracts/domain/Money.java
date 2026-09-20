package dev.aegis.contracts.domain;

import dev.aegis.shared.InvalidInputException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * A contract amount with decimal precision and an explicit currency. Rounding is never implicit: the amount is
 * only normalized to one canonical form (trailing zeros removed, at least the two decimal places money is written
 * with), so the same amount reads the same whether it comes from a member, the database or an export.
 */
public record Money(BigDecimal amount, Currency currency) {
    public static final int MAX_SCALE = 4;
    private static final int MIN_SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.scale() > MAX_SCALE) {
            throw new InvalidInputException("contract.amount.precision",
                    "Amount must not have more than " + MAX_SCALE + " decimal places");
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        amount = stripped.scale() < MIN_SCALE ? stripped.setScale(MIN_SCALE) : stripped;
    }

    public static Money of(String amount, String currencyCode) {
        try {
            return new Money(new BigDecimal(amount.trim()),
                    Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT)));
        } catch (ArithmeticException | IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("contract.amount.invalid", "Invalid amount or currency");
        }
    }

    /** Canonical, locale-independent form such as 49.90 EUR; used in APIs, exports and readable files. */
    public String canonicalText() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }

    /** Equality by numeric value and currency, so 49.9 EUR and 49.90 EUR are the same amount. */
    public boolean sameAs(Money other) {
        return other != null && currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }
}
