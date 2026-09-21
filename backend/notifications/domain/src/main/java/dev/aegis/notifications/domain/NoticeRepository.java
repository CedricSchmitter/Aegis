package dev.aegis.notifications.domain;

import dev.aegis.shared.MemberId;
import java.util.List;
import java.util.Optional;

/** Persistence of the per-member inbox. */
public interface NoticeRepository {
    Optional<Notice> findById(NoticeId id);

    /** Newest first. */
    List<Notice> findByRecipient(MemberId recipient, boolean unreadOnly);

    Optional<Notice> findByDedupKey(MemberId recipient, String dedupKey);

    long countUnread(MemberId recipient);

    void save(Notice notice);

    /** Atomically inserts unless this recipient already has the durable deduplication key. */
    boolean saveIfAbsent(Notice notice);
}
