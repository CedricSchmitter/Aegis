package dev.aegis.planning.adapters.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** JPA model of a task row; mapped explicitly from and to the aggregate. */
@Entity
@Table(name = "tasks")
public class TaskEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String note;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "planned_date")
    private LocalDate plannedDate;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Version
    private Long version;

    protected TaskEntity() {
    }

    TaskEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    void setTitle(String title) {
        this.title = title;
    }

    String getNote() {
        return note;
    }

    void setNote(String note) {
        this.note = note;
    }

    UUID getOwnerId() {
        return ownerId;
    }

    void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    LocalDate getPlannedDate() {
        return plannedDate;
    }

    void setPlannedDate(LocalDate plannedDate) {
        this.plannedDate = plannedDate;
    }

    UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    void setSourceDocumentId(UUID sourceDocumentId) {
        this.sourceDocumentId = sourceDocumentId;
    }

    UUID getCreatedBy() {
        return createdBy;
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

    Instant getCompletedAt() {
        return completedAt;
    }

    void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    UUID getCompletedBy() {
        return completedBy;
    }

    void setCompletedBy(UUID completedBy) {
        this.completedBy = completedBy;
    }

    Long getVersion() {
        return version;
    }
}
