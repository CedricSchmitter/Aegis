package dev.aegis.contracts.domain;

/**
 * Lifecycle of the contract itself, decided by a member. It is deliberately separate from the review state of
 * facts and from any task: marking a contract cancelled records what the household did, not what Aegis did.
 */
public enum ContractStatus {
    /** The contract is in force, or its end is not known to have passed. */
    ACTIVE,
    /** The household cancelled the contract; deadlines no longer need review. */
    CANCELLED,
    /** The contract has ended. */
    ENDED
}
