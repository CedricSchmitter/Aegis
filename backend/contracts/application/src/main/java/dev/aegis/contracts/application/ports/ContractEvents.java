package dev.aegis.contracts.application.ports;

import dev.aegis.shared.ContractId;

/** Outbound contract events, routed by the composition root to the notice inbox and the review inbox. */
public interface ContractEvents {
    /**
     * The contract was deleted permanently (ADR-0021). Runs in the transaction that deletes it, so notices and review
     * cards that only describe it disappear together with it.
     */
    void contractPurged(ContractId id);
}
