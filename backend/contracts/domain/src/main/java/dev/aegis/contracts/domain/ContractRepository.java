package dev.aegis.contracts.domain;

import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
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

    /**
     * Deletes the contract with all of its facts (ADR-0021) and records who deleted it and when, without its name or
     * facts.
     *
     * @throws dev.aegis.shared.StaleVersionException when the stored version differs from the aggregate version
     */
    void purge(Contract contract, MemberId purgedBy, Instant purgedAt);

    /** Whether a contract with this id was deleted permanently. */
    boolean wasPurged(ContractId id);
}
