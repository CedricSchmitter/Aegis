package dev.aegis.contracts.application;

import dev.aegis.contracts.domain.ContractStatus;
import dev.aegis.contracts.domain.FactId;
import dev.aegis.contracts.domain.FactOrigin;
import dev.aegis.contracts.domain.FactType;
import dev.aegis.contracts.domain.FactValue;
import dev.aegis.contracts.domain.ReviewState;
import dev.aegis.contracts.domain.SourceReference;
import dev.aegis.contracts.domain.ValidityPeriod;
import dev.aegis.documents.application.api.DocumentReference;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.TrashedException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Turns the stable English names and canonical texts of the public contract into domain types. Unknown input is a
 * validation error with a stable code, never a silently ignored value.
 */
final class ContractInputs {

    private ContractInputs() {
    }

    static FactType factType(String value) {
        try {
            return FactType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new InvalidInputException("contract.fact.type.unknown", "Unknown fact type " + value);
        }
    }

    static ContractStatus status(String value) {
        try {
            return ContractStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new InvalidInputException("contract.status.unknown", "Unknown contract status " + value);
        }
    }

    static ReviewState reviewState(String value) {
        try {
            return ReviewState.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new InvalidInputException("contract.fact.state.unknown", "Unknown review state " + value);
        }
    }

    static FactOrigin origin(String value) {
        try {
            return FactOrigin.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new InvalidInputException("contract.fact.origin.unknown", "Unknown fact origin " + value);
        }
    }

    static FactValue value(FactType type, String canonicalText) {
        return FactValue.parse(type.valueKind(), canonicalText);
    }

    static ValidityPeriod validity(LocalDate from, LocalDate until) {
        return from == null && until == null ? ValidityPeriod.always() : new ValidityPeriod(from, until);
    }

    static FactId factId(UUID value) {
        if (value == null) {
            throw new InvalidInputException("validation.failed", "Fact id is required");
        }
        return new FactId(value);
    }

    /**
     * The source reference of a fact, with the referenced document verified through the documents public
     * contract. A page or quote without a document is rejected instead of being stored without provenance.
     */
    static SourceReference source(DocumentId documentId, Integer page, String quote,
            DocumentReferenceQuery documents) {
        if (documentId == null) {
            if (page != null || (quote != null && !quote.isBlank())) {
                throw new InvalidInputException("contract.source.missing",
                        "A page or quote needs the document it was read in");
            }
            return null;
        }
        documents.findReference(documentId)
                .orElseThrow(() -> new NotFoundException("document", documentId.toString()));
        return new SourceReference(documentId, page, quote);
    }

    /**
     * The source of a fact a member states. A member cannot cite a document that is in the trash; it is restored
     * first. An extraction run that was already reading the document may still record its proposal.
     */
    static SourceReference memberSource(DocumentId documentId, Integer page, String quote,
            DocumentReferenceQuery documents) {
        SourceReference source = source(documentId, page, quote, documents);
        if (source != null && documents.findReference(documentId).map(DocumentReference::trashed).orElse(false)) {
            throw new TrashedException("document", documentId.toString());
        }
        return source;
    }
}
