package dev.aegis.planning.adapters.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TaskJpaRepository extends JpaRepository<TaskEntity, UUID> {
    @Query("select t from TaskEntity t order by case when t.status = 'OPEN' then 0 else 1 end, "
            + "t.dueDate asc nulls last, t.plannedDate asc nulls last, t.completedAt desc, t.createdAt asc")
    List<TaskEntity> findAllOrdered();

    @Query("select t from TaskEntity t where t.status = 'OPEN' and t.trashedAt is null "
            + "order by t.dueDate asc nulls last, t.plannedDate asc nulls last, t.createdAt asc")
    List<TaskEntity> findOpenOrdered();

    List<TaskEntity> findByTrashedAtIsNotNullOrderByTrashedAtDesc();

    List<TaskEntity> findBySourceDocumentIdOrderByCreatedAtAsc(UUID sourceDocumentId);

    List<TaskEntity> findByContractIdOrderByCancellationDeadlineAsc(UUID contractId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tasks WHERE id = :id AND version = :version", nativeQuery = true)
    int deleteVersion(@Param("id") UUID id, @Param("version") long version);

    @Modifying
    @Query(value = "INSERT INTO task_purges (task_id, purged_by, purged_at) VALUES (:id, :by, :at) "
            + "ON CONFLICT (task_id) DO NOTHING", nativeQuery = true)
    void recordPurge(@Param("id") UUID id, @Param("by") UUID by, @Param("at") Instant at);

    @Query(value = "SELECT count(*) > 0 FROM task_purges WHERE task_id = :id", nativeQuery = true)
    boolean existsPurge(@Param("id") UUID id);
}
