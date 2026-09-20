package dev.aegis.contracts.application.api;

import dev.aegis.shared.Actor;

/**
 * Restores a contract from an export with its stable id, its fact ids, review states and source relationships.
 * An existing contract is left untouched, so a repeated restore never overwrites newer local decisions.
 */
public interface ImportContract {
    enum ImportOutcome {
        IMPORTED,
        ALREADY_PRESENT
    }

    ImportOutcome importContract(Actor actor, ContractExportRecord record);
}
