package dev.aegis.contracts.domain;

import dev.aegis.shared.InvalidInputException;
import java.time.LocalDate;

/**
 * The period a fact describes the contract for, in household local dates. An open start means "since the contract
 * began", an open end means "until further notice". An amendment states a later start and therefore does not
 * replace the original fact; it takes over from its own start date.
 */
public record ValidityPeriod(LocalDate from, LocalDate until) {
    private static final ValidityPeriod ALWAYS = new ValidityPeriod(null, null);

    public ValidityPeriod {
        if (from != null && until != null && until.isBefore(from)) {
            throw new InvalidInputException("contract.validity.invalid", "Validity ends before it starts");
        }
    }

    /** The whole lifetime of the contract, used when a document states no period of its own. */
    public static ValidityPeriod always() {
        return ALWAYS;
    }

    public static ValidityPeriod from(LocalDate from) {
        return new ValidityPeriod(from, null);
    }

    public boolean covers(LocalDate date) {
        return !(from != null && date.isBefore(from)) && !(until != null && date.isAfter(until));
    }

    public boolean overlaps(ValidityPeriod other) {
        return !(until != null && other.from != null && until.isBefore(other.from))
                && !(other.until != null && from != null && other.until.isBefore(from));
    }

    /** Same range, so a newer confirmed fact replaces an older one instead of amending it. */
    public boolean sameRange(ValidityPeriod other) {
        return java.util.Objects.equals(from, other.from) && java.util.Objects.equals(until, other.until);
    }

    /** Start used for ordering; an open start is the earliest possible date. */
    public LocalDate effectiveFrom() {
        return from == null ? LocalDate.MIN : from;
    }
}
