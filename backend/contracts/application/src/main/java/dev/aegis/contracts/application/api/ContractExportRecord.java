package dev.aegis.contracts.application.api;

import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A contract as it leaves and re-enters Aegis: identity, status and every observation with its value, validity,
 * review state, provenance and the relationships between observations (what supersedes or corrects what).
 */
public record ContractExportRecord(ContractId id, String name, String status, MemberId createdBy, Instant createdAt,
        Instant updatedAt, List<FactExportRecord> facts) {

    public ContractExportRecord {
        facts = List.copyOf(facts);
    }

    /** {@code value} is the canonical form of the value; {@code valueKind} says how to read it. */
    public record FactExportRecord(UUID id, String type, String valueKind, String value, LocalDate validFrom,
            LocalDate validUntil, String reviewState, String origin, Double confidence, DocumentId sourceDocumentId,
            Integer sourcePage, String sourceQuote, String runRef, MemberId observedBy, Instant observedAt,
            MemberId decidedBy, Instant decidedAt, UUID supersededBy, UUID corrects, String note) {
    }
}
