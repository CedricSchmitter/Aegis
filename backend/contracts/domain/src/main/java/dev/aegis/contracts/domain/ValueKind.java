package dev.aegis.contracts.domain;

/** The shape of a fact value. Every {@link FactType} declares exactly one kind, which validation enforces. */
public enum ValueKind {
    /** Free text such as a counterparty name or a contract number. */
    TEXT,
    /** A local calendar date, for example a contract end or a cancellation deadline. */
    DATE,
    /** A decimal amount with an explicit currency. */
    MONEY,
    /** An ISO-8601 period such as {@code P3M}; never a duration of hours. */
    PERIOD,
    /** A yes/no rule such as automatic renewal. */
    FLAG
}
