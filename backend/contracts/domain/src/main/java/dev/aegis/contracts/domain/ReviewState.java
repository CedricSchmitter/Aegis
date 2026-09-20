package dev.aegis.contracts.domain;

/**
 * Review state of one observed fact. Only a confirmed fact is effective, so deadlines and answers never rest on an
 * unreviewed extraction. States are stored as they are; presentation is localized at the boundary.
 */
public enum ReviewState {
    /** Proposed by an extraction run and waiting for a member's decision. */
    EXTRACTED,
    /** Entered or confirmed by a member; the only state that counts as established. */
    CONFIRMED,
    /** A member decided the value is wrong; kept so later runs do not bring it back unnoticed. */
    REJECTED,
    /** Replaced by a newer confirmed fact for the same validity period; kept as history. */
    SUPERSEDED;

    /** Whether the fact counts as established knowledge about the contract. */
    public boolean effective() {
        return this == CONFIRMED;
    }

    /** Whether the fact is waiting for a member's decision. */
    public boolean pending() {
        return this == EXTRACTED;
    }
}
