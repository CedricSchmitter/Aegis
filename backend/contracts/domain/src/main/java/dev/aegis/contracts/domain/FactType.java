package dev.aegis.contracts.domain;

/**
 * The contract facts Aegis stores explicitly. Every type fixes the shape of its value, so a notice period can
 * never be stored as free text and a date can never be stored as an amount.
 */
public enum FactType {
    /** The other party of the contract, for example the studio, insurer or provider. */
    COUNTERPARTY(ValueKind.TEXT),
    /** The counterparty's reference for this contract (customer or contract number). */
    CONTRACT_NUMBER(ValueKind.TEXT),
    /** First day the contract is in force. */
    START_DATE(ValueKind.DATE),
    /** Last day the contract is in force, where the contract states one. */
    END_DATE(ValueKind.DATE),
    /** Minimum term agreed at the start, for example P24M. */
    MINIMUM_TERM(ValueKind.PERIOD),
    /** Period the contract extends by when nobody cancels. */
    RENEWAL_TERM(ValueKind.PERIOD),
    /** How long before the end a cancellation must have arrived, for example P3M. */
    NOTICE_PERIOD(ValueKind.PERIOD),
    /** Whether the contract renews automatically. */
    AUTOMATIC_RENEWAL(ValueKind.FLAG),
    /** The last day a cancellation can be sent, where a document states the date itself. */
    CANCELLATION_DEADLINE(ValueKind.DATE),
    /** Amount per billing period. */
    AMOUNT(ValueKind.MONEY),
    /** How often the amount is charged, for example P1M. */
    BILLING_PERIOD(ValueKind.PERIOD);

    private final ValueKind valueKind;

    FactType(ValueKind valueKind) {
        this.valueKind = valueKind;
    }

    public ValueKind valueKind() {
        return valueKind;
    }
}
