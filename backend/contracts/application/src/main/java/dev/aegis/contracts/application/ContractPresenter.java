package dev.aegis.contracts.application;

import dev.aegis.contracts.application.api.ContractExportRecord;
import dev.aegis.contracts.application.api.ContractExportRecord.FactExportRecord;
import dev.aegis.contracts.application.api.ContractViews.ConflictView;
import dev.aegis.contracts.application.api.ContractViews.ContractSummary;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.contracts.application.api.ContractViews.DeadlineView;
import dev.aegis.contracts.application.api.ContractViews.FactView;
import dev.aegis.contracts.application.api.ContractViews.SourceView;
import dev.aegis.contracts.domain.Confidence;
import dev.aegis.contracts.domain.Contract;
import dev.aegis.contracts.domain.ContractFact;
import dev.aegis.contracts.domain.FactConflict;
import dev.aegis.contracts.domain.FactId;
import dev.aegis.contracts.domain.FactType;
import dev.aegis.contracts.domain.FactValue;
import dev.aegis.contracts.domain.SourceReference;
import dev.aegis.contracts.domain.deadline.CancellationDeadline;
import dev.aegis.contracts.domain.deadline.CancellationDeadlinePolicy;
import dev.aegis.documents.application.api.DocumentReference;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-model mapping for the contracts context. Domain enumerations become their stable English names and source
 * documents are resolved through the documents public contract, never through its tables.
 */
public class ContractPresenter {
    private final DocumentReferenceQuery documents;

    public ContractPresenter(DocumentReferenceQuery documents) {
        this.documents = documents;
    }

    public ContractSummary summarize(Contract contract, LocalDate on) {
        DeadlineView deadline = deadline(contract, on);
        return new ContractSummary(contract.id(), contract.name(), counterparty(contract, on).orElse(null),
                contract.status().name(), deadline.contractEnd(), deadline, contract.pendingFacts().size(),
                contract.conflicts(on).size(), contract.updatedAt(), contract.version());
    }

    public ContractView describe(Contract contract, LocalDate on) {
        List<FactView> facts = contract.facts().stream()
                .sorted(Comparator.comparing((ContractFact fact) -> fact.type().name())
                        .thenComparing(fact -> fact.provenance().observedAt(), Comparator.reverseOrder()))
                .map(this::factView).toList();
        List<ConflictView> conflicts = contract.conflicts(on).stream().map(ContractPresenter::conflictView).toList();
        List<SourceView> sources = contract.sources().stream().map(this::sourceView).toList();
        return new ContractView(contract.id(), contract.name(), counterparty(contract, on).orElse(null),
                contract.status().name(), contract.createdBy(), contract.createdAt(), contract.updatedAt(),
                contract.version(), on, deadline(contract, on), facts, conflicts, sources);
    }

    public DeadlineView deadline(Contract contract, LocalDate on) {
        CancellationDeadline result = CancellationDeadlinePolicy.calculate(contract, on);
        if (result instanceof CancellationDeadline.Determined determined) {
            return new DeadlineView(true, determined.deadline(), determined.contractEnd(),
                    determined.basis().name(), ids(determined.basedOn()), List.of(), List.of());
        }
        CancellationDeadline.Insufficient insufficient = (CancellationDeadline.Insufficient) result;
        return new DeadlineView(false, null, contractEnd(contract, on).orElse(null), null, List.of(),
                names(insufficient.missing()), names(insufficient.contradicting()));
    }

    public ContractExportRecord exportRecord(Contract contract) {
        List<FactExportRecord> facts = contract.facts().stream().map(ContractPresenter::factExportRecord).toList();
        return new ContractExportRecord(contract.id(), contract.name(), contract.status().name(),
                contract.createdBy(), contract.createdAt(), contract.updatedAt(), facts);
    }

    /** The confirmed counterparty on that day, if any; used for list entries and deadline views. */
    public Optional<String> counterparty(Contract contract, LocalDate on) {
        return contract.effective(FactType.COUNTERPARTY, on).map(fact -> fact.value().canonicalText());
    }

    /** The confirmed contract end on that day, if any. */
    public Optional<LocalDate> contractEnd(Contract contract, LocalDate on) {
        return contract.effective(FactType.END_DATE, on)
                .map(fact -> ((FactValue.DateValue) fact.value()).date());
    }

    public FactView factView(ContractFact fact) {
        return new FactView(fact.id().value(), fact.type().name(), fact.value().kind().name(),
                fact.value().canonicalText(), fact.validity().from(), fact.validity().until(),
                fact.state().name(), fact.provenance().origin().name(),
                Optional.ofNullable(fact.provenance().confidence()).map(Confidence::value).orElse(null),
                fact.provenance().sourceReference().map(this::sourceView).orElse(null),
                fact.provenance().runRef(), fact.provenance().observedBy(), fact.provenance().observedAt(),
                fact.decidedBy().orElse(null), fact.decidedAt().orElse(null),
                fact.supersededBy().map(FactId::value).orElse(null), fact.corrects().map(FactId::value).orElse(null),
                fact.note(), fact.effective());
    }

    public SourceView sourceView(SourceReference source) {
        return new SourceView(source.documentId(),
                documents.findReference(source.documentId()).map(DocumentReference::title).orElse(null),
                source.page(), source.quote());
    }

    private static FactExportRecord factExportRecord(ContractFact fact) {
        SourceReference source = fact.provenance().sourceReference().orElse(null);
        return new FactExportRecord(fact.id().value(), fact.type().name(), fact.value().kind().name(),
                fact.value().canonicalText(), fact.validity().from(), fact.validity().until(),
                fact.state().name(), fact.provenance().origin().name(),
                Optional.ofNullable(fact.provenance().confidence()).map(Confidence::value).orElse(null),
                source == null ? null : source.documentId(), source == null ? null : source.page(),
                source == null ? null : source.quote(), fact.provenance().runRef(),
                fact.provenance().observedBy(), fact.provenance().observedAt(), fact.decidedBy().orElse(null),
                fact.decidedAt().orElse(null), fact.supersededBy().map(FactId::value).orElse(null),
                fact.corrects().map(FactId::value).orElse(null), fact.note());
    }

    private static ConflictView conflictView(FactConflict conflict) {
        return new ConflictView(conflict.type().name(), conflict.kind().name(),
                conflict.established() == null ? null : conflict.established().id().value(),
                conflict.conflicting().stream().map(fact -> fact.id().value()).toList());
    }

    private static List<UUID> ids(List<FactId> factIds) {
        return factIds.stream().map(FactId::value).toList();
    }

    private static List<String> names(java.util.Set<FactType> types) {
        return types.stream().map(FactType::name).sorted().toList();
    }
}
