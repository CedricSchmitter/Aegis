package dev.aegis.assistant.domain;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The household's own cost limits of one workload: {@code dailyLimit} caps the cost of the workload's model calls per
 * provider and household day, {@code runLimit} is the cost budget of each of its runs (one question, one document
 * classification, one review). Version 0 means the household has set no limits of its own and the configured
 * defaults apply; once set, both limits apply to every provider of the workload. Every change needs the version the
 * member saw and records who changed it and when.
 */
public final class WorkloadCostLimits {
    /** Upper bound of a limit, against typing errors that would remove the cost protection. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000");

    private final Workload workload;
    private Money dailyLimit;
    private Money runLimit;
    private long version;
    private MemberId changedBy;
    private Instant changedAt;

    private WorkloadCostLimits(Workload workload, Money dailyLimit, Money runLimit, long version, MemberId changedBy,
            Instant changedAt) {
        this.workload = Objects.requireNonNull(workload, "workload");
        if (workload == Workload.CONNECTION_CHECK) {
            throw new IllegalArgumentException("the connection check has no cost limits of its own");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (version == 0 ? dailyLimit != null || runLimit != null : dailyLimit == null || runLimit == null) {
            throw new IllegalArgumentException("stored cost limits need both amounts, defaults none");
        }
        this.dailyLimit = dailyLimit;
        this.runLimit = runLimit;
        this.version = version;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    /** A workload whose cost limits nobody has set; the configured defaults apply. */
    public static WorkloadCostLimits defaultOf(Workload workload) {
        return new WorkloadCostLimits(workload, null, null, 0, null, null);
    }

    public static WorkloadCostLimits restore(Workload workload, Money dailyLimit, Money runLimit, long version,
            MemberId changedBy, Instant changedAt) {
        return new WorkloadCostLimits(workload, dailyLimit, runLimit, version, changedBy, changedAt);
    }

    /**
     * Sets both limits of the workload. Each is a positive amount in whole cents (the currency's minor unit) of at
     * most {@link #MAX_AMOUNT}; a run may not cost more than the day allows.
     *
     * @return whether the limits changed; setting the current limits again changes nothing
     * @throws StaleVersionException when {@code expectedVersion} is not the current version
     * @throws InvalidInputException when an amount is not a valid limit
     */
    public boolean change(Money dailyLimit, Money runLimit, long expectedVersion, MemberId by, Instant at) {
        Objects.requireNonNull(by, "member");
        Objects.requireNonNull(at, "time");
        if (expectedVersion != version) {
            throw new StaleVersionException("aiCostLimits", workload.name(), expectedVersion, version);
        }
        requireValid("dailyCostLimit", dailyLimit);
        requireValid("maxRunCost", runLimit);
        if (!dailyLimit.currency().equals(runLimit.currency())) {
            throw new IllegalArgumentException("both limits need the same currency");
        }
        if (runLimit.exceeds(dailyLimit)) {
            throw new InvalidInputException("ai.limits.runAboveDaily",
                    "The session limit must not be higher than the daily limit",
                    Map.of("workload", workload.name()));
        }
        if (version > 0 && dailyLimit.equals(this.dailyLimit) && runLimit.equals(this.runLimit)) {
            return false;
        }
        this.dailyLimit = dailyLimit;
        this.runLimit = runLimit;
        this.version = version + 1;
        this.changedBy = by;
        this.changedAt = at;
        return true;
    }

    private void requireValid(String field, Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw invalid("ai.limits.notPositive", "A cost limit must be a positive amount", field);
        }
        int minorDigits = Math.max(0, amount.currency().getDefaultFractionDigits());
        if (amount.amount().stripTrailingZeros().scale() > minorDigits) {
            throw invalid("ai.limits.tooPrecise", "A cost limit has at most " + minorDigits + " decimals", field);
        }
        if (amount.amount().compareTo(MAX_AMOUNT) > 0) {
            throw invalid("ai.limits.tooHigh", "A cost limit is at most " + MAX_AMOUNT.toPlainString(), field);
        }
    }

    private InvalidInputException invalid(String code, String message, String field) {
        return new InvalidInputException(code, message, Map.of("workload", workload.name(), "field", field));
    }

    public Workload workload() {
        return workload;
    }

    /** Whether the household has set limits of its own; otherwise the configured defaults apply. */
    public boolean own() {
        return version > 0;
    }

    public Optional<Money> dailyLimit() {
        return Optional.ofNullable(dailyLimit);
    }

    public Optional<Money> runLimit() {
        return Optional.ofNullable(runLimit);
    }

    public long version() {
        return version;
    }

    public Optional<MemberId> changedBy() {
        return Optional.ofNullable(changedBy);
    }

    public Optional<Instant> changedAt() {
        return Optional.ofNullable(changedAt);
    }
}
