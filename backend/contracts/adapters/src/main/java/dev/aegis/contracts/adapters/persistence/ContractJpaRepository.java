package dev.aegis.contracts.adapters.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ContractJpaRepository extends JpaRepository<ContractEntity, UUID> {
    @Query("select c from ContractEntity c order by c.createdAt desc, c.name asc")
    List<ContractEntity> findAllOrdered();
}
