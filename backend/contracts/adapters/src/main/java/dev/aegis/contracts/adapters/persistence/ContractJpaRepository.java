package dev.aegis.contracts.adapters.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ContractJpaRepository extends JpaRepository<ContractEntity, UUID> {
    @Query("select c from ContractEntity c order by c.createdAt desc, c.name asc")
    List<ContractEntity> findAllOrdered();

    /** Deletes the contract if it still has this version; its facts follow through the foreign key cascade. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM contracts WHERE id = :id AND version = :version", nativeQuery = true)
    int deleteVersion(@Param("id") UUID id, @Param("version") long version);

    @Modifying
    @Query(value = "INSERT INTO contract_purges (contract_id, purged_by, purged_at) VALUES (:id, :by, :at) "
            + "ON CONFLICT (contract_id) DO NOTHING", nativeQuery = true)
    void recordPurge(@Param("id") UUID id, @Param("by") UUID by, @Param("at") Instant at);

    @Query(value = "SELECT count(*) > 0 FROM contract_purges WHERE contract_id = :id", nativeQuery = true)
    boolean existsPurge(@Param("id") UUID id);
}
