package dev.aegis.contracts.adapters.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ContractFactJpaRepository extends JpaRepository<ContractFactEntity, UUID> {
    List<ContractFactEntity> findByContractIdOrderByObservedAtAsc(UUID contractId);

    List<ContractFactEntity> findByContractIdInOrderByObservedAtAsc(Collection<UUID> contractIds);

    @Query("select distinct f.contractId from ContractFactEntity f where f.sourceDocumentId = :documentId")
    List<UUID> findContractIdsBySourceDocument(UUID documentId);
}
