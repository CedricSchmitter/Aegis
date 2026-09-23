package dev.aegis.contracts.application;

import dev.aegis.contracts.domain.Contract;
import dev.aegis.contracts.domain.ContractFact;
import dev.aegis.contracts.domain.ContractRepository;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.StaleVersionException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test double that behaves like the JPA repository: each save increments the version, a stale version is
 * rejected, and every read hands out a fresh aggregate so a caller cannot see another caller's unsaved edits.
 * PostgreSQL semantics themselves are covered by the integration tests.
 */
public class InMemoryContractRepository implements ContractRepository {
    private final Map<ContractId, Contract> store = new LinkedHashMap<>();

    @Override
    public Optional<Contract> findById(ContractId id) {
        return Optional.ofNullable(store.get(id)).map(InMemoryContractRepository::copy);
    }

    @Override
    public List<Contract> findAll() {
        return store.values().stream().map(InMemoryContractRepository::copy)
                .sorted(Comparator.comparing(Contract::createdAt).reversed()).toList();
    }

    @Override
    public List<Contract> findBySourceDocument(DocumentId documentId) {
        return findAll().stream().filter(contract -> contract.sources().stream()
                .anyMatch(source -> source.documentId().equals(documentId))).toList();
    }

    @Override
    public void save(Contract contract) {
        Contract stored = store.get(contract.id());
        if (stored != null && stored.version() != contract.version()) {
            throw new StaleVersionException("contract", contract.id().toString(), contract.version(),
                    stored.version());
        }
        store.put(contract.id(), copy(contract, contract.version() + 1));
    }

    private static Contract copy(Contract contract) {
        return copy(contract, contract.version());
    }

    private static Contract copy(Contract contract, long version) {
        List<ContractFact> facts = new ArrayList<>();
        for (ContractFact fact : contract.facts()) {
            facts.add(ContractFact.rehydrate(fact.id(), fact.type(), fact.value(), fact.validity(),
                    fact.provenance(), fact.state(), fact.decidedBy().orElse(null), fact.decidedAt().orElse(null),
                    fact.supersededBy().orElse(null), fact.corrects().orElse(null), fact.note()));
        }
        return Contract.rehydrate(contract.id(), contract.name(), contract.status(), contract.createdBy(),
                contract.createdAt(), contract.updatedAt(), facts, contract.trash().orElse(null), version);
    }
}
