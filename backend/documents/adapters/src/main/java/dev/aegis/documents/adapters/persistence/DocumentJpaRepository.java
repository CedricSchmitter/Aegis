package dev.aegis.documents.adapters.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    List<DocumentEntity> findByPurgeRequestedAtIsNotNullAndPurgeFailedAtIsNullOrderByPurgeRequestedAtAsc();

    @Modifying
    @Query(value = "INSERT INTO document_purges (document_id, requested_by, requested_at, purged_at) "
            + "VALUES (:id, :requestedBy, :requestedAt, :purgedAt) ON CONFLICT (document_id) DO NOTHING",
            nativeQuery = true)
    void recordPurge(@Param("id") UUID id, @Param("requestedBy") UUID requestedBy,
            @Param("requestedAt") Instant requestedAt, @Param("purgedAt") Instant purgedAt);

    @Query(value = "SELECT count(*) > 0 FROM document_purges WHERE document_id = :id", nativeQuery = true)
    boolean existsPurge(@Param("id") UUID id);
}
