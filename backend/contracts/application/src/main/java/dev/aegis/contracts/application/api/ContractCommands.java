package dev.aegis.contracts.application.api;

import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a household member can do with contract facts. Every command is a member decision: entering, correcting,
 * confirming and rejecting are the only ways a fact becomes established, which is why the feature works without
 * AI. Version-checked commands reject an outdated view instead of overwriting the other member's change.
 */
public interface ContractCommands {

    /**
     * A new contract. The counterparty is optional and, when given, stored as a confirmed fact like any other,
     * with the source document as its reference.
     */
    record CreateContractCommand(String name, String counterparty, DocumentId sourceDocumentId) {
    }

    /**
     * A fact a member states. {@code value} is the canonical form for the type ({@code 2028-01-01}, {@code P3M},
     * {@code 29.90 EUR}, {@code true} or free text). An open {@code validFrom} means since the contract began, an
     * open {@code validUntil} until further notice; an amendment states its own start date. The page is only
     * stored when the member actually read the fact there.
     */
    record EnterFactCommand(String type, String value, LocalDate validFrom, LocalDate validUntil,
            DocumentId sourceDocumentId, Integer sourcePage, String sourceQuote, String note) {
    }

    /** A corrected value for an observation; the corrected one stays as superseded history. */
    record CorrectFactCommand(String value, LocalDate validFrom, LocalDate validUntil, String note) {
    }

    ContractView create(Actor actor, CreateContractCommand command);

    ContractView rename(Actor actor, ContractId id, String name, long expectedVersion);

    /** Records what the household did with the contract, for example that it was cancelled. */
    ContractView changeStatus(Actor actor, ContractId id, String status, long expectedVersion);

    ContractView enterFact(Actor actor, ContractId id, EnterFactCommand command, long expectedVersion);

    ContractView confirmFact(Actor actor, ContractId id, UUID factId, long expectedVersion);

    ContractView rejectFact(Actor actor, ContractId id, UUID factId, long expectedVersion);

    ContractView correctFact(Actor actor, ContractId id, UUID factId, CorrectFactCommand command,
            long expectedVersion);

    /**
     * Moves the contract to the trash (ADR-0010). It keeps every fact; its deadline is no longer published, so its
     * open cancellation review is superseded. Repeating it for a trashed contract returns it unchanged.
     */
    ContractView moveToTrash(Actor actor, ContractId id, long expectedVersion);

    /**
     * Brings a trashed contract back; its deadline is published again and reopens the superseded review.
     * Repeating it for a contract that is not in the trash returns it unchanged.
     */
    ContractView restoreFromTrash(Actor actor, ContractId id, long expectedVersion);

    /**
     * Deletes a trashed contract permanently with all of its facts (ADR-0021). Completed cancellation reviews stay as
     * history and show that the contract was deleted; superseded automatic reviews are deleted with it. Repeating it
     * for a contract that is already gone changes nothing.
     */
    void purge(Actor actor, ContractId id, long expectedVersion);
}
