package dev.aegis.identity.adapters.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA persistence model for members. Separate from the domain aggregate; mapped explicitly. */
@Entity
@Table(name = "members")
public class MemberEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 40)
    private String username;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(nullable = false, length = 8)
    private String locale;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected MemberEntity() {
    }

    MemberEntity(UUID id, String username, String displayName, String passwordHash, String locale, boolean active,
            String kind, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.locale = locale;
        this.active = active;
        this.kind = kind;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    String getUsername() {
        return username;
    }

    String getDisplayName() {
        return displayName;
    }

    void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    String getLocale() {
        return locale;
    }

    void setLocale(String locale) {
        this.locale = locale;
    }

    boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    String getKind() {
        return kind;
    }

    void setKind(String kind) {
        this.kind = kind;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
