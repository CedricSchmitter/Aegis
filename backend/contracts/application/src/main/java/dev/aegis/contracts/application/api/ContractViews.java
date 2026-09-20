package dev.aegis.contracts.application.api;

import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read models of the contracts context. Enumerations cross this boundary as their stable English names, never as
 * domain types, so other contexts and adapters localize presentation without depending on the contracts model.
 */
public final class ContractViews {

    private ContractViews() {
    }

    /** Where a fact was read; the title is resolved through the documents contract and may be unknown. */
    public record SourceView(DocumentId documentId, String documentTitle, Integer page, String quote) {
    }

    /**
     * One observation with its provenance and review state. {@code value} is the canonical, locale-independent
     * form of the value ({@code 2028-01-01}, {@code P3M}, {@code 29.90 EUR}); presentation formats it.
     */
    public record FactView(UUID id, String type, String valueKind, String value, LocalDate validFrom,
            LocalDate validUntil, String reviewState, String origin, Double confidence, SourceView source,
            String runRef, MemberId observedBy, Instant observedAt, MemberId decidedBy, Instant decidedAt,
            UUID supersededBy, UUID corrects, String note, boolean effective) {
    }

    /** Statements that contradict each other and need a decision; Aegis never resolves them on its own. */
    public record ConflictView(String type, String kind, UUID establishedFactId, List<UUID> conflictingFactIds) {
        public ConflictView {
            conflictingFactIds = List.copyOf(conflictingFactIds);
        }
    }

    /**
     * The contractual cancellation deadline as far as confirmed facts establish it. When it is not determined,
     * {@code missing} and {@code contradicting} say why, so a review can name the open question.
     */
    public record DeadlineView(boolean determined, LocalDate deadline, LocalDate contractEnd, String basis,
            List<UUID> basedOn, List<String> missing, List<String> contradicting) {
        public DeadlineView {
            basedOn = List.copyOf(basedOn);
            missing = List.copyOf(missing);
            contradicting = List.copyOf(contradicting);
        }
    }

    /** List entry: what the household needs to recognize a contract and see whether it needs attention. */
    public record ContractSummary(ContractId id, String name, String counterparty, String status,
            LocalDate contractEnd, DeadlineView deadline, int pendingFacts, int conflicts, Instant updatedAt,
            long version) {
    }

    /** Full contract with every observation, its sources and the open contradictions. */
    public record ContractView(ContractId id, String name, String counterparty, String status, MemberId createdBy,
            Instant createdAt, Instant updatedAt, long version, LocalDate evaluatedOn, DeadlineView deadline,
            List<FactView> facts, List<ConflictView> conflicts, List<SourceView> sources) {
        public ContractView {
            facts = List.copyOf(facts);
            conflicts = List.copyOf(conflicts);
            sources = List.copyOf(sources);
        }
    }
}
