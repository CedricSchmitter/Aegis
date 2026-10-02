package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public contract for portability: where the pages of documents created by the page editor came from (ADR-0024).
 * Derivations are household knowledge and travel by Aegis document id; the page-edit operations they name are not
 * exported.
 */
public interface DerivationPortability {

    /**
     * Result pages {@code firstPage}..{@code lastPage} (one-based) are source pages
     * {@code firstSourcePage}..{@code lastSourcePage}, turned by {@code rotation}. {@code sourceKind} is
     * {@code DOCUMENT} or {@code SCAN}; {@code source} is null for scanned pages and for a deleted source.
     */
    record RangeRecord(int firstPage, int lastPage, String sourceKind, DocumentId source, int firstSourcePage,
            int lastSourcePage, int rotation) {
    }

    record DerivationRecord(DocumentId document, UUID pageEdit, MemberId createdBy, Instant createdAt,
            List<RangeRecord> ranges) {
    }

    enum ImportOutcome {
        IMPORTED,
        ALREADY_PRESENT,
        /** The created document is not stored here, for example because the export lacked its original. */
        DOCUMENT_MISSING
    }

    /** {@code sourcesCleared} counts ranges whose source is not stored here and that now name a deleted source. */
    record ImportResult(ImportOutcome outcome, int sourcesCleared) {
    }

    List<DerivationRecord> exportAll(Actor actor);

    /**
     * Restores an exported derivation after the documents are restored. A document that already has a derivation
     * keeps it, so a repeated restore changes nothing.
     */
    ImportResult importDerivation(Actor actor, DerivationRecord record);
}
