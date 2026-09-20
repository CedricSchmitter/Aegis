package dev.aegis.contracts.domain;

import java.util.List;
import java.util.Objects;

/**
 * Two statements about the same fact type that cannot both describe the contract on the same day. Aegis reports
 * the conflict instead of choosing: a contradicting invoice never overwrites what a member confirmed.
 */
public record FactConflict(FactType type, ConflictKind kind, ContractFact established, List<ContractFact> conflicting) {

    public enum ConflictKind {
        /** Several confirmed facts describe the same day with different values; a member has to resolve this. */
        CONFIRMED_AGAINST_CONFIRMED,
        /** An extraction contradicts a confirmed fact; the confirmed value stays effective until a decision. */
        EXTRACTED_AGAINST_CONFIRMED
    }

    public FactConflict {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        conflicting = List.copyOf(conflicting);
    }
}
