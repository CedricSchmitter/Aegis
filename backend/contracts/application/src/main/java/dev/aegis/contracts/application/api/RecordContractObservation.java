package dev.aegis.contracts.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import java.time.LocalDate;
import java.util.UUID;

/**
 * How an extraction run hands a proposed fact to the contracts context. A proposal is always
 * stored for review and never changes a confirmed fact; a page number is only stored when the run actually read
 * the value there, and a quote must come from the document text.
 */
public interface RecordContractObservation {

    record ObservationCommand(String type, String value, LocalDate validFrom, LocalDate validUntil,
            DocumentId sourceDocumentId, Integer sourcePage, String sourceQuote, Double confidence, String runRef) {
    }

    /**
     * The stored proposal, or the already known fact it repeats. {@code outcome} is one of {@code RECORDED},
     * {@code CONTRADICTS_CONFIRMED}, {@code ALREADY_CONFIRMED} or {@code ALREADY_PROPOSED}, so a caller can tell
     * a new review item from a repeated import.
     */
    record ObservationResult(UUID factId, String outcome, String reviewState) {
    }

    ObservationResult observe(Actor actor, ContractId contractId, ObservationCommand command);
}
