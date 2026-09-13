package dev.aegis.notifications.adapters.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA model of a notice row. Parameters are stored as a small JSON object. */
@Entity
@Table(name = "notices")
public class NoticeEntity {
    @Id
    private UUID id;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(name = "dedup_key", nullable = false, length = 200)
    private String dedupKey;

    @Column(name = "params_json", nullable = false, columnDefinition = "text")
    private String paramsJson;

    @Column(name = "link_type", length = 20)
    private String linkType;

    @Column(name = "link_id", length = 64)
    private String linkId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected NoticeEntity() {
    }

    NoticeEntity(UUID id, UUID recipientId, String kind, String dedupKey, String paramsJson, String linkType,
            String linkId, Instant createdAt, Instant readAt) {
        this.id = id;
        this.recipientId = recipientId;
        this.kind = kind;
        this.dedupKey = dedupKey;
        this.paramsJson = paramsJson;
        this.linkType = linkType;
        this.linkId = linkId;
        this.createdAt = createdAt;
        this.readAt = readAt;
    }

    UUID getId() {
        return id;
    }

    UUID getRecipientId() {
        return recipientId;
    }

    String getKind() {
        return kind;
    }

    String getDedupKey() {
        return dedupKey;
    }

    String getParamsJson() {
        return paramsJson;
    }

    String getLinkType() {
        return linkType;
    }

    String getLinkId() {
        return linkId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getReadAt() {
        return readAt;
    }

    void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }
}
