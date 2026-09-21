package dev.aegis.planning.adapters.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TaskJpaRepository extends JpaRepository<TaskEntity, UUID> {
    @Query("select t from TaskEntity t order by case when t.status = 'OPEN' then 0 else 1 end, "
            + "t.dueDate asc nulls last, t.plannedDate asc nulls last, t.completedAt desc, t.createdAt asc")
    List<TaskEntity> findAllOrdered();

    @Query("select t from TaskEntity t where t.status = 'OPEN' order by t.dueDate asc nulls last, "
            + "t.plannedDate asc nulls last, t.createdAt asc")
    List<TaskEntity> findOpenOrdered();

    List<TaskEntity> findBySourceDocumentIdOrderByCreatedAtAsc(UUID sourceDocumentId);

    List<TaskEntity> findByContractIdOrderByCancellationDeadlineAsc(UUID contractId);
}
