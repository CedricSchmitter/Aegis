package dev.aegis.notifications.domain;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persistence of the per-member inbox. */
public interface NoticeRepository {
    Optional<Notice> findById(NoticeId id);

    /** The recipient's notices of the given kinds, newest first. */
    List<Notice> findByRecipient(MemberId recipient, Set<NoticeKind> kinds, boolean unreadOnly);

    /** The recipient's unread notices created at or after {@code since}, oldest first. */
    List<Notice> findUnreadCreatedSince(MemberId recipient, Instant since);

    Optional<Notice> findByDedupKey(MemberId recipient, String dedupKey);

    long countUnread(MemberId recipient, Set<NoticeKind> kinds);

    void save(Notice notice);

    /** Atomically inserts unless this recipient already has the durable deduplication key. */
    boolean saveIfAbsent(Notice notice);

    /** Deletes every notice that opens this target, for all recipients; returns how many were deleted. */
    int deleteLinkedTo(NoticeLink link);
}
