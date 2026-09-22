package dev.aegis.contracts.adapters.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA model of a contract row; mapped explicitly from and to the aggregate. */
@Entity
@Table(name = "contracts")
public class ContractEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "trashed_by")
    private UUID trashedBy;

    @Column(name = "trashed_at")
    private Instant trashedAt;

    @Column(name = "restorable_until")
    private Instant restorableUntil;

    @Version
    private Long version;

    protected ContractEntity() {
    }

    ContractEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    UUID getTrashedBy() {
        return trashedBy;
    }

    Instant getTrashedAt() {
        return trashedAt;
    }

    Instant getRestorableUntil() {
        return restorableUntil;
    }

    void setTrash(UUID trashedBy, Instant trashedAt, Instant restorableUntil) {
        this.trashedBy = trashedBy;
        this.trashedAt = trashedAt;
        this.restorableUntil = restorableUntil;
    }

    void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    Long getVersion() {
        return version;
    }
}
