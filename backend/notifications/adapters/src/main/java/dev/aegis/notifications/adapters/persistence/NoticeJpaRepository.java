package dev.aegis.notifications.adapters.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NoticeJpaRepository extends JpaRepository<NoticeEntity, UUID> {
    List<NoticeEntity> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId);

    List<NoticeEntity> findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(UUID recipientId);

    Optional<NoticeEntity> findFirstByRecipientIdAndDedupKeyAndReadAtIsNull(UUID recipientId, String dedupKey);

    long countByRecipientIdAndReadAtIsNull(UUID recipientId);
}
