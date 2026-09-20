package dev.aegis.contracts.domain;

import dev.aegis.shared.InvalidInputException;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * The value of a contract fact. Every value carries a canonical, locale-independent text form so that persistence,
 * the API and exports agree on one representation; presentation formatting happens at the boundary.
 */
public sealed interface FactValue {

    ValueKind kind();

    String canonicalText();

    /** Value equality that ignores irrelevant differences such as the scale of an amount. */
    boolean sameAs(FactValue other);

    record TextValue(String text) implements FactValue {
        public static final int MAX_LENGTH = 500;

        public TextValue {
            if (text == null || text.isBlank()) {
                throw new InvalidInputException("contract.fact.value.missing", "Value is required");
            }
            text = text.trim();
            if (text.length() > MAX_LENGTH) {
                throw new InvalidInputException("contract.fact.value.tooLong", "Value exceeds " + MAX_LENGTH);
            }
        }

        @Override
        public ValueKind kind() {
            return ValueKind.TEXT;
        }

        @Override
        public String canonicalText() {
            return text;
        }

        @Override
        public boolean sameAs(FactValue other) {
            return other instanceof TextValue value && value.text.equalsIgnoreCase(text);
        }
    }

    record DateValue(LocalDate date) implements FactValue {
        public DateValue {
            Objects.requireNonNull(date, "date");
        }

        @Override
        public ValueKind kind() {
            return ValueKind.DATE;
        }

        @Override
        public String canonicalText() {
            return date.toString();
        }

        @Override
        public boolean sameAs(FactValue other) {
            return other instanceof DateValue value && value.date.equals(date);
        }
    }

    record MoneyValue(Money money) implements FactValue {
        public MoneyValue {
            Objects.requireNonNull(money, "money");
        }

        @Override
        public ValueKind kind() {
            return ValueKind.MONEY;
        }

        @Override
        public String canonicalText() {
            return money.canonicalText();
        }

        @Override
        public boolean sameAs(FactValue other) {
            return other instanceof MoneyValue value && value.money.sameAs(money);
        }
    }

    /** A contractual period such as a notice period or a renewal term; always positive. */
    record PeriodValue(Period period) implements FactValue {
        public PeriodValue {
            Objects.requireNonNull(period, "period");
            if (period.isZero() || period.isNegative()) {
                throw new InvalidInputException("contract.fact.period.invalid", "Period must be positive");
            }
        }

        @Override
        public ValueKind kind() {
            return ValueKind.PERIOD;
        }

        @Override
        public String canonicalText() {
            return period.normalized().toString();
        }

        @Override
        public boolean sameAs(FactValue other) {
            return other instanceof PeriodValue value && value.canonicalText().equals(canonicalText());
        }
    }

    record FlagValue(boolean flag) implements FactValue {
        @Override
        public ValueKind kind() {
            return ValueKind.FLAG;
        }

        @Override
        public String canonicalText() {
            return Boolean.toString(flag);
        }

        @Override
        public boolean sameAs(FactValue other) {
            return other instanceof FlagValue value && value.flag == flag;
        }
    }

    /** Parses the canonical text form of a kind; invalid input is a validation error, never a silent default. */
    static FactValue parse(ValueKind kind, String canonicalText) {
        if (canonicalText == null || canonicalText.isBlank()) {
            throw new InvalidInputException("contract.fact.value.missing", "Value is required");
        }
        String text = canonicalText.trim();
        try {
            return switch (kind) {
                case TEXT -> new TextValue(text);
                case DATE -> new DateValue(LocalDate.parse(text));
                case PERIOD -> new PeriodValue(Period.parse(text));
                case MONEY -> money(text);
                case FLAG -> flag(text);
            };
        } catch (DateTimeParseException invalid) {
            throw new InvalidInputException("contract.fact.value.invalid", "Value does not match " + kind);
        }
    }

    private static FactValue money(String text) {
        String[] parts = text.split("\s+");
        if (parts.length != 2) {
            throw new InvalidInputException("contract.amount.invalid", "Expected an amount and a currency code");
        }
        return new MoneyValue(Money.of(parts[0], parts[1]));
    }

    private static FactValue flag(String text) {
        if (text.equalsIgnoreCase("true")) {
            return new FlagValue(true);
        }
        if (text.equalsIgnoreCase("false")) {
            return new FlagValue(false);
        }
        throw new InvalidInputException("contract.fact.value.invalid", "Expected true or false");
    }
}
