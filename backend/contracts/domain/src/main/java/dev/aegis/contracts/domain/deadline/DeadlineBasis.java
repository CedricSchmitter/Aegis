package dev.aegis.contracts.domain.deadline;

/** Which confirmed rule a calculated cancellation deadline rests on. Shown with the deadline, never hidden. */
public enum DeadlineBasis {
    /** A document states the last cancellation date itself; it is used unchanged. */
    STATED_DEADLINE,
    /** Calculated as the contract end minus the confirmed notice period. */
    NOTICE_PERIOD_BEFORE_END
}
