package dev.aegis.contracts.adapters.rest;

import dev.aegis.contracts.application.api.ContractViews.ConflictView;
import dev.aegis.contracts.application.api.ContractViews.ContractSummary;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.contracts.application.api.ContractViews.DeadlineView;
import dev.aegis.contracts.application.api.ContractViews.FactView;
import dev.aegis.contracts.application.api.ContractViews.SourceView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * HTTP representation of a contract. Enumerations and values travel as stable English codes and canonical texts;
 * the frontend renders them in German or English. Dates stay calendar dates, amounts keep their currency.
 */
public record ContractDto(String id, String name, @Schema(nullable = true) String counterparty, String status,
        String createdBy, Instant createdAt, Instant updatedAt, long version, LocalDate evaluatedOn,
        DeadlineDto deadline, List<FactDto> facts, List<ConflictDto> conflicts, List<SourceDto> sources) {

    /** Where a fact was read. The title is null when the document is no longer available. */
    public record SourceDto(String documentId, @Schema(nullable = true) String documentTitle,
            @Schema(nullable = true) Integer page, @Schema(nullable = true) String quote) {
    }

    /**
     * One observation. {@code value} is the canonical form for {@code valueKind}: an ISO date, an ISO period, an
     * amount with its currency, {@code true}/{@code false} or free text.
     */
    public record FactDto(String id, String type, String valueKind, String value,
            @Schema(nullable = true) LocalDate validFrom, @Schema(nullable = true) LocalDate validUntil,
            String reviewState, String origin, @Schema(nullable = true) Double confidence,
            @Schema(nullable = true) SourceDto source, @Schema(nullable = true) String runRef,
            @Schema(nullable = true) String observedBy, Instant observedAt,
            @Schema(nullable = true) String decidedBy, @Schema(nullable = true) Instant decidedAt,
            @Schema(nullable = true) String supersededBy, @Schema(nullable = true) String corrects, String note,
            boolean effective) {
    }

    /** Statements that contradict each other; the household decides, Aegis does not. */
    public record ConflictDto(String type, String kind, @Schema(nullable = true) String establishedFactId,
            List<String> conflictingFactIds) {
    }

    /**
     * The contractual deadline, or why it cannot be calculated. It is never a planned work or reminder date.
     */
    public record DeadlineDto(boolean determined, @Schema(nullable = true) LocalDate deadline,
            @Schema(nullable = true) LocalDate contractEnd, @Schema(nullable = true) String basis,
            List<String> basedOn, List<String> missing, List<String> contradicting) {
    }

    /** List entry with the deadline state, so the overview shows what needs attention. */
    public record SummaryDto(String id, String name, @Schema(nullable = true) String counterparty, String status,
            @Schema(nullable = true) LocalDate contractEnd, DeadlineDto deadline, int pendingFacts, int conflicts,
            Instant updatedAt, long version) {
    }

    public static ContractDto from(ContractView view) {
        return new ContractDto(view.id().toString(), view.name(), view.counterparty(), view.status(),
                view.createdBy().toString(), view.createdAt(), view.updatedAt(), view.version(),
                view.evaluatedOn(), deadline(view.deadline()), view.facts().stream().map(ContractDto::fact).toList(),
                view.conflicts().stream().map(ContractDto::conflict).toList(),
                view.sources().stream().map(ContractDto::source).toList());
    }

    public static SummaryDto from(ContractSummary summary) {
        return new SummaryDto(summary.id().toString(), summary.name(), summary.counterparty(), summary.status(),
                summary.contractEnd(), deadline(summary.deadline()), summary.pendingFacts(), summary.conflicts(),
                summary.updatedAt(), summary.version());
    }

    private static FactDto fact(FactView fact) {
        return new FactDto(fact.id().toString(), fact.type(), fact.valueKind(), fact.value(), fact.validFrom(),
                fact.validUntil(), fact.reviewState(), fact.origin(), fact.confidence(),
                fact.source() == null ? null : source(fact.source()), fact.runRef(),
                fact.observedBy() == null ? null : fact.observedBy().toString(), fact.observedAt(),
                fact.decidedBy() == null ? null : fact.decidedBy().toString(), fact.decidedAt(),
                fact.supersededBy() == null ? null : fact.supersededBy().toString(),
                fact.corrects() == null ? null : fact.corrects().toString(), fact.note(), fact.effective());
    }

    private static SourceDto source(SourceView source) {
        return new SourceDto(source.documentId().toString(), source.documentTitle(), source.page(), source.quote());
    }

    private static ConflictDto conflict(ConflictView conflict) {
        return new ConflictDto(conflict.type(), conflict.kind(),
                conflict.establishedFactId() == null ? null : conflict.establishedFactId().toString(),
                conflict.conflictingFactIds().stream().map(Object::toString).toList());
    }

    private static DeadlineDto deadline(DeadlineView deadline) {
        return new DeadlineDto(deadline.determined(), deadline.deadline(), deadline.contractEnd(),
                deadline.basis(), deadline.basedOn().stream().map(Object::toString).toList(), deadline.missing(),
                deadline.contradicting());
    }
}
