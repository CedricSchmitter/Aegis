package dev.aegis.contracts.domain;

import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.Optional;

/** Aggregate persistence for contracts and their facts, with optimistic locking on the contract version. */
public interface ContractRepository {

    Optional<Contract> findById(ContractId id);

    /** All contracts, newest first; facts included. */
    List<Contract> findAll();

    /** Contracts with at least one fact taken from that document. */
    List<Contract> findBySourceDocument(DocumentId documentId);

    /**
     * Persists the aggregate with all of its facts.
     *
     * @throws dev.aegis.shared.StaleVersionException when the stored version differs from the aggregate version
     */
    void save(Contract contract);
}
