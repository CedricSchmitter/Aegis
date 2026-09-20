package dev.aegis.contracts.adapters.persistence;

import dev.aegis.contracts.domain.Confidence;
import dev.aegis.contracts.domain.Contract;
import dev.aegis.contracts.domain.ContractFact;
import dev.aegis.contracts.domain.ContractRepository;
import dev.aegis.contracts.domain.ContractStatus;
import dev.aegis.contracts.domain.FactId;
import dev.aegis.contracts.domain.FactOrigin;
import dev.aegis.contracts.domain.FactProvenance;
import dev.aegis.contracts.domain.FactType;
import dev.aegis.contracts.domain.FactValue;
import dev.aegis.contracts.domain.Money;
import dev.aegis.contracts.domain.ReviewState;
import dev.aegis.contracts.domain.SourceReference;
import dev.aegis.contracts.domain.ValidityPeriod;
import dev.aegis.contracts.domain.ValueKind;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Period;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Domain repository over JPA. Facts are appended and their review state updated; nothing is deleted, so the
 * observation history survives every edit. Optimistic locking failures become {@link StaleVersionException}.
 */
@Component
public class JpaContractRepository implements ContractRepository {
    private final ContractJpaRepository contracts;
    private final ContractFactJpaRepository facts;

    JpaContractRepository(ContractJpaRepository contracts, ContractFactJpaRepository facts) {
        this.contracts = contracts;
        this.facts = facts;
    }

    @Override
    public Optional<Contract> findById(ContractId id) {
        return contracts.findById(id.value())
                .map(entity -> toDomain(entity, facts.findByContractIdOrderByObservedAtAsc(entity.getId())));
    }

    @Override
    public List<Contract> findAll() {
        return withFacts(contracts.findAllOrdered());
    }

    @Override
    public List<Contract> findBySourceDocument(DocumentId documentId) {
        List<UUID> ids = facts.findContractIdsBySourceDocument(documentId.value());
        if (ids.isEmpty()) {
            return List.of();
        }
        return withFacts(contracts.findAllOrdered().stream().filter(entity -> ids.contains(entity.getId()))
                .toList());
    }

    @Override
    public void save(Contract contract) {
        ContractEntity entity = contracts.findById(contract.id().value())
                .orElseGet(() -> new ContractEntity(contract.id().value()));
        if (entity.getVersion() != null && entity.getVersion() != contract.version()) {
            throw new StaleVersionException("contract", contract.id().toString(), contract.version(),
                    entity.getVersion());
        }
        entity.setName(contract.name());
        entity.setStatus(contract.status().name());
        entity.setCreatedBy(contract.createdBy().value());
        entity.setCreatedAt(contract.createdAt());
        entity.setUpdatedAt(contract.updatedAt());
        try {
            contracts.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException concurrent) {
            throw new StaleVersionException("contract", contract.id().toString(), contract.version(), -1);
        }
        saveFacts(contract);
    }

    private void saveFacts(Contract contract) {
        Map<UUID, ContractFactEntity> stored = new LinkedHashMap<>();
        facts.findByContractIdOrderByObservedAtAsc(contract.id().value())
                .forEach(entity -> stored.put(entity.getId(), entity));
        List<ContractFactEntity> changed = new ArrayList<>();
        for (ContractFact fact : contract.facts()) {
            ContractFactEntity entity = stored.get(fact.id().value());
            if (entity == null) {
                entity = new ContractFactEntity(fact.id().value(), contract.id().value());
                writeObservation(entity, fact);
            }
            writeDecision(entity, fact);
            changed.add(entity);
        }
        facts.saveAll(changed);
        facts.flush();
    }

    /** The observed statement itself; it never changes, because a correction is a new fact. */
    private static void writeObservation(ContractFactEntity entity, ContractFact fact) {
        entity.setFactType(fact.type().name());
        entity.setValueKind(fact.value().kind().name());
        writeValue(entity, fact.value());
        entity.setValidFrom(fact.validity().from());
        entity.setValidUntil(fact.validity().until());
        FactProvenance provenance = fact.provenance();
        entity.setOrigin(provenance.origin().name());
        entity.setConfidence(provenance.confidence() == null ? null : provenance.confidence().value());
        entity.setRunRef(provenance.runRef());
        entity.setObservedBy(provenance.observedBy() == null ? null : provenance.observedBy().value());
        entity.setObservedAt(provenance.observedAt());
        SourceReference source = provenance.sourceReference().orElse(null);
        entity.setSourceDocumentId(source == null ? null : source.documentId().value());
        entity.setSourcePage(source == null ? null : source.page());
        entity.setSourceQuote(source == null ? null : source.quote());
        entity.setCorrects(fact.corrects().map(FactId::value).orElse(null));
        entity.setNote(fact.note());
    }

    /** What a member decided about the statement, and which fact replaced it. */
    private static void writeDecision(ContractFactEntity entity, ContractFact fact) {
        entity.setReviewState(fact.state().name());
        entity.setDecidedBy(fact.decidedBy().map(MemberId::value).orElse(null));
        entity.setDecidedAt(fact.decidedAt().orElse(null));
        entity.setSupersededBy(fact.supersededBy().map(FactId::value).orElse(null));
    }

    private static void writeValue(ContractFactEntity entity, FactValue value) {
        switch (value) {
            case FactValue.TextValue text -> entity.setValueText(text.text());
            case FactValue.DateValue date -> entity.setValueDate(date.date());
            case FactValue.PeriodValue period -> entity.setValuePeriod(period.canonicalText());
            case FactValue.FlagValue flag -> entity.setValueFlag(flag.flag());
            case FactValue.MoneyValue money -> {
                entity.setValueAmount(money.money().amount());
                entity.setValueCurrency(money.money().currency().getCurrencyCode());
            }
        }
    }

    private List<Contract> withFacts(List<ContractEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ContractFactEntity>> byContract = new LinkedHashMap<>();
        facts.findByContractIdInOrderByObservedAtAsc(entities.stream().map(ContractEntity::getId).toList())
                .forEach(fact -> byContract.computeIfAbsent(fact.getContractId(), id -> new ArrayList<>())
                        .add(fact));
        return entities.stream()
                .map(entity -> toDomain(entity, byContract.getOrDefault(entity.getId(), List.of()))).toList();
    }

    private static Contract toDomain(ContractEntity entity, List<ContractFactEntity> factEntities) {
        List<ContractFact> domainFacts = factEntities.stream().map(JpaContractRepository::toDomain).toList();
        return Contract.rehydrate(new ContractId(entity.getId()), entity.getName(),
                ContractStatus.valueOf(entity.getStatus()), new MemberId(entity.getCreatedBy()),
                entity.getCreatedAt(), entity.getUpdatedAt(), domainFacts,
                entity.getVersion() == null ? 0 : entity.getVersion());
    }

    private static ContractFact toDomain(ContractFactEntity entity) {
        SourceReference source = entity.getSourceDocumentId() == null ? null
                : new SourceReference(new DocumentId(entity.getSourceDocumentId()), entity.getSourcePage(),
                        entity.getSourceQuote());
        FactProvenance provenance = new FactProvenance(FactOrigin.valueOf(entity.getOrigin()), source,
                entity.getConfidence() == null ? null : new Confidence(entity.getConfidence()), entity.getRunRef(),
                entity.getObservedBy() == null ? null : new MemberId(entity.getObservedBy()),
                entity.getObservedAt());
        return ContractFact.rehydrate(new FactId(entity.getId()), FactType.valueOf(entity.getFactType()),
                readValue(entity), new ValidityPeriod(entity.getValidFrom(), entity.getValidUntil()), provenance,
                ReviewState.valueOf(entity.getReviewState()),
                entity.getDecidedBy() == null ? null : new MemberId(entity.getDecidedBy()), entity.getDecidedAt(),
                entity.getSupersededBy() == null ? null : new FactId(entity.getSupersededBy()),
                entity.getCorrects() == null ? null : new FactId(entity.getCorrects()), entity.getNote());
    }

    private static FactValue readValue(ContractFactEntity entity) {
        return switch (ValueKind.valueOf(entity.getValueKind())) {
            case TEXT -> new FactValue.TextValue(entity.getValueText());
            case DATE -> new FactValue.DateValue(entity.getValueDate());
            case PERIOD -> new FactValue.PeriodValue(Period.parse(entity.getValuePeriod()));
            case FLAG -> new FactValue.FlagValue(Boolean.TRUE.equals(entity.getValueFlag()));
            case MONEY -> new FactValue.MoneyValue(new Money(entity.getValueAmount(),
                    Currency.getInstance(entity.getValueCurrency())));
        };
    }
}
