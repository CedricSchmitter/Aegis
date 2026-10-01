package dev.aegis.contracts.application;

import dev.aegis.contracts.application.api.ContractCommands;
import dev.aegis.contracts.application.api.ContractExportRecord;
import dev.aegis.contracts.application.api.ContractExportRecord.FactExportRecord;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.contracts.application.api.ImportContract;
import dev.aegis.contracts.application.api.RecordContractObservation;
import dev.aegis.contracts.application.api.ContractReconciliationScheduler;
import dev.aegis.contracts.application.ports.ContractEvents;
import dev.aegis.contracts.domain.Confidence;
import dev.aegis.contracts.domain.Contract;
import dev.aegis.contracts.domain.ContractFact;
import dev.aegis.contracts.domain.ContractRepository;
import dev.aegis.contracts.domain.FactId;
import dev.aegis.contracts.domain.FactProvenance;
import dev.aegis.contracts.domain.FactType;
import dev.aegis.contracts.domain.FactValue;
import dev.aegis.contracts.domain.SourceReference;
import dev.aegis.contracts.domain.ValidityPeriod;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.PurgeConflictException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.Trash;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Contract facts as members and extraction runs change them. Members decide: entering, correcting, confirming and
 * rejecting are member commands, while an extraction run may only propose. The composition root wraps this
 * service in a transaction, so a decision and the superseded history it produces commit together.
 */
public class ContractService implements ContractCommands, RecordContractObservation, ImportContract {
    private final ContractRepository contracts;
    private final DocumentReferenceQuery documents;
    private final ContractPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;
    private final ContractReconciliationScheduler reconciliation;
    private final Trash.RecoveryWindow recoveryWindow;
    private final ContractEvents events;

    public ContractService(ContractRepository contracts, DocumentReferenceQuery documents,
            ContractPresenter presenter, HouseholdAccess access, Clock clock,
            ContractReconciliationScheduler reconciliation, Trash.RecoveryWindow recoveryWindow,
            ContractEvents events) {
        this.contracts = contracts;
        this.documents = documents;
        this.presenter = presenter;
        this.access = access;
        this.clock = clock;
        this.reconciliation = reconciliation;
        this.recoveryWindow = recoveryWindow;
        this.events = events;
    }

    @Override
    public ContractView create(Actor actor, CreateContractCommand command) {
        access.requireMember(actor);
        Instant now = clock.instant();
        Contract contract = Contract.create(command.name(), actor.memberId(), now);
        SourceReference source = ContractInputs.memberSource(command.sourceDocumentId(), null, null, documents);
        if (command.counterparty() != null && !command.counterparty().isBlank()) {
            contract.enter(FactType.COUNTERPARTY, new FactValue.TextValue(command.counterparty()),
                    ValidityPeriod.always(), source, null, actor.memberId(), now);
        }
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(contract.id());
    }

    @Override
    public ContractView rename(Actor actor, ContractId id, String name, long expectedVersion) {
        access.requireMember(actor);
        Contract contract = loadVersion(id, expectedVersion);
        contract.rename(name, clock.instant());
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(id);
    }

    @Override
    public ContractView changeStatus(Actor actor, ContractId id, String status, long expectedVersion) {
        access.requireMember(actor);
        Contract contract = loadVersion(id, expectedVersion);
        contract.changeStatus(ContractInputs.status(status), clock.instant());
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(id);
    }

    @Override
    public ContractView enterFact(Actor actor, ContractId id, EnterFactCommand command, long expectedVersion) {
        access.requireMember(actor);
        Contract contract = loadVersion(id, expectedVersion);
        FactType type = ContractInputs.factType(command.type());
        contract.enter(type, ContractInputs.value(type, command.value()),
                ContractInputs.validity(command.validFrom(), command.validUntil()),
                ContractInputs.memberSource(command.sourceDocumentId(), command.sourcePage(),
                        command.sourceQuote(), documents),
                command.note(), actor.memberId(), clock.instant());
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(id);
    }

    @Override
    public ContractView confirmFact(Actor actor, ContractId id, UUID factId, long expectedVersion) {
        access.requireMember(actor);
        Contract contract = loadVersion(id, expectedVersion);
        contract.confirm(ContractInputs.factId(factId), actor.memberId(), clock.instant());
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(id);
    }

    @Override
    public ContractView rejectFact(Actor actor, ContractId id, UUID factId, long expectedVersion) {
        access.requireMember(actor);
        Contract contract = loadVersion(id, expectedVersion);
        contract.reject(ContractInputs.factId(factId), actor.memberId(), clock.instant());
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(id);
    }

    @Override
    public ContractView correctFact(Actor actor, ContractId id, UUID factId, CorrectFactCommand command,
            long expectedVersion) {
        access.requireMember(actor);
        Contract contract = loadVersion(id, expectedVersion);
        FactId corrected = ContractInputs.factId(factId);
        FactType type = contract.fact(corrected)
                .orElseThrow(() -> new NotFoundException("contract.fact", corrected.toString())).type();
        contract.correct(corrected, ContractInputs.value(type, command.value()),
                ContractInputs.validity(command.validFrom(), command.validUntil()), command.note(),
                actor.memberId(), clock.instant());
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(id);
    }

    @Override
    public ContractView moveToTrash(Actor actor, ContractId id, long expectedVersion) {
        access.requireMember(actor);
        Contract contract = load(id);
        if (contract.isTrashed()) {
            return describe(id);
        }
        requireVersion(contract, expectedVersion);
        contract.moveToTrash(actor.memberId(), clock.instant(), recoveryWindow);
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(id);
    }

    @Override
    public ContractView restoreFromTrash(Actor actor, ContractId id, long expectedVersion) {
        access.requireMember(actor);
        Contract contract = load(id);
        if (!contract.isTrashed()) {
            return describe(id);
        }
        requireVersion(contract, expectedVersion);
        contract.restoreFromTrash(clock.instant());
        contracts.save(contract);
        reconcile(contract, actor);
        return describe(id);
    }

    @Override
    public void purge(Actor actor, ContractId id, long expectedVersion) {
        access.requireMember(actor);
        Contract contract = contracts.findById(id).orElse(null);
        if (contract == null) {
            if (contracts.wasPurged(id)) {
                return;
            }
            throw new NotFoundException("contract", id.toString());
        }
        if (!contract.isTrashed()) {
            throw PurgeConflictException.notTrashed("contract", id.toString());
        }
        requireVersion(contract, expectedVersion);
        contracts.purge(contract, actor.memberId(), clock.instant());
        // Planning removes the superseded reviews and marks the completed ones in its own job, atomically scheduled.
        reconciliation.schedule(id, actor.memberId());
        events.contractPurged(id);
    }

    @Override
    public ObservationResult observe(Actor actor, ContractId contractId, ObservationCommand command) {
        access.requireMember(actor);
        Contract contract = load(contractId);
        FactType type = ContractInputs.factType(command.type());
        SourceReference source = ContractInputs.source(command.sourceDocumentId(), command.sourcePage(),
                command.sourceQuote(), documents);
        if (source == null) {
            throw new InvalidInputException("contract.source.missing",
                    "An extracted fact must reference its source document");
        }
        Contract.Observation observation = contract.observe(type, ContractInputs.value(type, command.value()),
                ContractInputs.validity(command.validFrom(), command.validUntil()), source,
                command.confidence() == null ? null : new Confidence(command.confidence()), command.runRef(),
                clock.instant());
        contracts.save(contract);
        return new ObservationResult(observation.fact().id().value(), observation.outcome().name(),
                observation.fact().state().name());
    }

    @Override
    public ImportOutcome importContract(Actor actor, ContractExportRecord record) {
        access.requireMember(actor);
        if (contracts.findById(record.id()).isPresent()) {
            return ImportOutcome.ALREADY_PRESENT;
        }
        List<ContractFact> facts = new ArrayList<>();
        for (FactExportRecord fact : record.facts()) {
            facts.add(importedFact(fact));
        }
        Instant createdAt = record.createdAt() == null ? clock.instant() : record.createdAt();
        Contract contract = Contract.rehydrate(record.id(), record.name(), ContractInputs.status(record.status()),
                record.createdBy() == null ? actor.memberId() : record.createdBy(), createdAt, record.updatedAt(),
                facts, record.trash(), 0);
        contracts.save(contract);
        reconcile(contract, actor);
        return ImportOutcome.IMPORTED;
    }

    private void reconcile(Contract contract, Actor actor) {
        reconciliation.schedule(contract.id(), actor.memberId());
    }

    private ContractFact importedFact(FactExportRecord record) {
        FactType type = ContractInputs.factType(record.type());
        FactValue value = ContractInputs.value(type, record.value());
        if (record.valueKind() != null && !record.valueKind().isBlank()
                && !value.kind().name().equalsIgnoreCase(record.valueKind().trim())) {
            throw new InvalidInputException("contract.fact.value.kind",
                    "Fact " + type + " expects a " + type.valueKind() + " value");
        }
        SourceReference source = record.sourceDocumentId() == null ? null
                : new SourceReference(record.sourceDocumentId(), record.sourcePage(), record.sourceQuote());
        FactProvenance provenance = new FactProvenance(ContractInputs.origin(record.origin()), source,
                record.confidence() == null ? null : new Confidence(record.confidence()), record.runRef(),
                record.observedBy(), record.observedAt() == null ? clock.instant() : record.observedAt());
        return ContractFact.rehydrate(new FactId(record.id()), type, value,
                ContractInputs.validity(record.validFrom(), record.validUntil()),
                provenance, ContractInputs.reviewState(record.reviewState()), record.decidedBy(),
                record.decidedAt(), record.supersededBy() == null ? null : new FactId(record.supersededBy()),
                record.corrects() == null ? null : new FactId(record.corrects()), record.note());
    }

    /** Describes the aggregate as it was persisted, so the returned version is the stored one. */
    private ContractView describe(ContractId id) {
        return presenter.describe(load(id), today());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private Contract load(ContractId id) {
        return contracts.findById(id).orElseThrow(() -> new NotFoundException("contract", id.toString()));
    }

    private Contract loadVersion(ContractId id, long expectedVersion) {
        Contract contract = load(id);
        requireVersion(contract, expectedVersion);
        return contract;
    }

    private static void requireVersion(Contract contract, long expectedVersion) {
        if (contract.version() != expectedVersion) {
            throw new StaleVersionException("contract", contract.id().toString(), expectedVersion,
                    contract.version());
        }
    }
}
