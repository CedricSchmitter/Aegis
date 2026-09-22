package dev.aegis.documents.adapters.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {
    Optional<DocumentEntity> findByChecksumSha256(String checksum);

    List<DocumentEntity> findAllByOrderByUploadedAtDesc();

    List<DocumentEntity> findByIdIn(Collection<UUID> ids);

    @Query("select d from DocumentEntity d where lower(d.title) like lower(concat('%', :fragment, '%')) "
            + "order by d.uploadedAt desc")
    List<DocumentEntity> searchTitle(@Param("fragment") String fragment);

    List<DocumentEntity> findByPaperlessDocumentIdInOrderByUploadedAtDesc(List<Integer> paperlessIds);

    List<DocumentEntity> findByStateOrderByUploadedAtDesc(String state);

    List<DocumentEntity> findByStateAndStagingPresentTrueAndArchivedAtBefore(String state, Instant archivedBefore);

    List<DocumentEntity> findByTrashedAtIsNotNullOrderByTrashedAtDesc();
}
