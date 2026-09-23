package dev.aegis.contracts.application;

import dev.aegis.contracts.application.api.ContractDeadlineQuery;
import dev.aegis.contracts.application.api.ContractExportQuery;
import dev.aegis.contracts.application.api.ContractExportRecord;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.contracts.application.api.ContractViews.ContractSummary;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.contracts.domain.Contract;
import dev.aegis.contracts.domain.ContractFact;
import dev.aegis.contracts.domain.ContractRepository;
import dev.aegis.contracts.domain.FactId;
import dev.aegis.contracts.domain.SourceReference;
import dev.aegis.contracts.domain.deadline.CancellationDeadline;
import dev.aegis.contracts.domain.deadline.CancellationDeadlinePolicy;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reading contracts, their facts and the deadlines other contexts consume. Queries never change anything; a
 * deadline is calculated from the confirmed facts on the household day the caller asks about.
 */
public class ContractQueryService implements ContractQueries, ContractDeadlineQuery, ContractExportQuery {
    private final ContractRepository contracts;
    private final ContractPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;

    public ContractQueryService(ContractRepository contracts, ContractPresenter presenter, HouseholdAccess access,
            Clock clock) {
        this.contracts = contracts;
        this.presenter = presenter;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public List<ContractSummary> list(Actor actor) {
        // Open to household processes, like get: the review reconciler reads contract facts (ADR-0009).
        access.requireReader(actor);
        LocalDate today = today();
        return contracts.findAll().stream().filter(contract -> !contract.isTrashed())
                .map(contract -> presenter.summarize(contract, today)).toList();
    }

    @Override
    public List<ContractSummary> trashed(Actor actor) {
        access.requireMember(actor);
        LocalDate today = today();
        return contracts.findAll().stream().filter(Contract::isTrashed)
                .sorted(Comparator.comparing((Contract contract) -> contract.trash().orElseThrow().trashedAt())
                        .reversed())
                .map(contract -> presenter.summarize(contract, today)).toList();
    }

    @Override
    public ContractView get(Actor actor, ContractId id) {
        access.requireReader(actor);
        return presenter.describe(load(id), today());
    }

    @Override
    public List<ContractSummary> forDocument(Actor actor, DocumentId documentId) {
        access.requireMember(actor);
        LocalDate today = today();
        return contracts.findBySourceDocument(documentId).stream().filter(contract -> !contract.isTrashed())
                .map(contract -> presenter.summarize(contract, today)).toList();
    }

    @Override
    public List<ContractDeadlineView> confirmedDeadlines(Actor actor, LocalDate on) {
        access.requireMember(actor);
        LocalDate day = on == null ? today() : on;
        return contracts.findAll().stream().map(contract -> deadlineView(contract, day))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(ContractDeadlineView::cancellationDeadline))
                .toList();
    }

    @Override
    public Optional<ContractDeadlineView> confirmedDeadline(Actor actor, ContractId contractId, LocalDate on) {
        access.requireMember(actor);
        return deadlineView(load(contractId), on == null ? today() : on);
    }

    @Override
    public List<ContractExportRecord> exportAll(Actor actor) {
        access.requireMember(actor);
        return contracts.findAll().stream().map(presenter::exportRecord).toList();
    }

    /**
     * Empty unless the confirmed facts establish a deadline on that day; nothing is estimated. A contract in the
     * trash publishes no deadline, so planning supersedes its open review until the contract is restored.
     */
    private Optional<ContractDeadlineView> deadlineView(Contract contract, LocalDate on) {
        if (contract.isTrashed()) {
            return Optional.empty();
        }
        if (!(CancellationDeadlinePolicy.calculate(contract, on)
                instanceof CancellationDeadline.Determined determined)) {
            return Optional.empty();
        }
        List<java.util.UUID> basedOn = determined.basedOn().stream().map(FactId::value).toList();
        return Optional.of(new ContractDeadlineView(contract.id(), contract.name(),
                presenter.counterparty(contract, on).orElse(null), determined.contractEnd(),
                determined.deadline(), determined.basis().name(), basedOn,
                sourceDocument(contract, determined.basedOn()).orElse(null), contract.status().name(),
                contract.version(), contract.updatedAt()));
    }

    /** The document the deadline rests on: the first fact it was calculated from that names one. */
    private Optional<DocumentId> sourceDocument(Contract contract, List<FactId> basedOn) {
        return basedOn.stream().map(contract::fact).flatMap(Optional::stream)
                .map(ContractFact::provenance).map(provenance -> provenance.sourceReference().orElse(null))
                .filter(java.util.Objects::nonNull).map(SourceReference::documentId).findFirst();
    }

    private Contract load(ContractId id) {
        return contracts.findById(id).orElseThrow(() -> new NotFoundException("contract", id.toString()));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
