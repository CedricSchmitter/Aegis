package dev.aegis.notifications.domain;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A persisted in-app notice for one recipient. Read state belongs to the recipient and is independent from the
 * underlying task or document state; delivery to other channels is not claimed here.
 */
public final class Notice {
    private final NoticeId id;
    private final MemberId recipient;
    private final NoticeKind kind;
    private final String dedupKey;
    private final Map<String, String> params;
    private final NoticeLink link;
    private final Instant createdAt;
    private Instant readAt;

    private Notice(NoticeId id, MemberId recipient, NoticeKind kind, String dedupKey, Map<String, String> params,
            NoticeLink link, Instant createdAt, Instant readAt) {
        this.id = Objects.requireNonNull(id);
        this.recipient = Objects.requireNonNull(recipient);
        this.kind = Objects.requireNonNull(kind);
        this.dedupKey = Objects.requireNonNull(dedupKey);
        this.params = new LinkedHashMap<>(params == null ? Map.of() : params);
        this.link = link;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.readAt = readAt;
    }

    public static Notice create(MemberId recipient, NoticeKind kind, String dedupKey, Map<String, String> params,
            NoticeLink link, Instant now) {
        return new Notice(NoticeId.random(), recipient, kind, dedupKey, params, link, now, null);
    }

    public static Notice rehydrate(NoticeId id, MemberId recipient, NoticeKind kind, String dedupKey,
            Map<String, String> params, NoticeLink link, Instant createdAt, Instant readAt) {
        return new Notice(id, recipient, kind, dedupKey, params, link, createdAt, readAt);
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = Objects.requireNonNull(now);
        }
    }

    public boolean isUnread() {
        return readAt == null;
    }

    public NoticeId id() {
        return id;
    }

    public MemberId recipient() {
        return recipient;
    }

    public NoticeKind kind() {
        return kind;
    }

    public String dedupKey() {
        return dedupKey;
    }

    public Map<String, String> params() {
        return Collections.unmodifiableMap(params);
    }

    public Optional<NoticeLink> link() {
        return Optional.ofNullable(link);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> readAt() {
        return Optional.ofNullable(readAt);
    }
}
