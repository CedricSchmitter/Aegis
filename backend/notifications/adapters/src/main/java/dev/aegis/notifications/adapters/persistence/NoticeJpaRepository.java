package dev.aegis.notifications.adapters.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NoticeJpaRepository extends JpaRepository<NoticeEntity, UUID> {
    List<NoticeEntity> findByRecipientIdAndKindInOrderByCreatedAtDesc(UUID recipientId, Collection<String> kinds);

    List<NoticeEntity> findByRecipientIdAndKindInAndReadAtIsNullOrderByCreatedAtDesc(UUID recipientId,
            Collection<String> kinds);

    Optional<NoticeEntity> findFirstByRecipientIdAndDedupKey(UUID recipientId, String dedupKey);

    long countByRecipientIdAndKindInAndReadAtIsNull(UUID recipientId, Collection<String> kinds);

    @Modifying
    @Query(value = "DELETE FROM notices WHERE link_type = :linkType AND link_id = :linkId", nativeQuery = true)
    int deleteByLink(@Param("linkType") String linkType, @Param("linkId") String linkId);

    @Modifying
    @Query(value = """
            INSERT INTO notices (id, recipient_id, kind, dedup_key, params_json, link_type, link_id, created_at)
            VALUES (:id, :recipientId, :kind, :dedupKey, :paramsJson, :linkType, :linkId, :createdAt)
            ON CONFLICT (recipient_id, dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("recipientId") UUID recipientId,
            @Param("kind") String kind, @Param("dedupKey") String dedupKey,
            @Param("paramsJson") String paramsJson, @Param("linkType") String linkType,
            @Param("linkId") String linkId, @Param("createdAt") Instant createdAt);
}
