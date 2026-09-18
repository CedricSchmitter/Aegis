package dev.aegis.documents.adapters.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** JPA model of a document record. Mapped explicitly to and from the domain aggregate. */
@Entity
@Table(name = "documents")
public class DocumentEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "title_origin", nullable = false, length = 20)
    private String titleOrigin;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "staging_path", nullable = false, length = 255)
    private String stagingPath;

    @Column(name = "staging_present", nullable = false)
    private boolean stagingPresent;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_failure_code", length = 100)
    private String lastFailureCode;

    @Column(name = "last_failure_message", length = 500)
    private String lastFailureMessage;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "paperless_task_id", length = 64)
    private String paperlessTaskId;

    @Column(name = "paperless_document_id")
    private Integer paperlessDocumentId;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    private Long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "document_subjects", joinColumns = @JoinColumn(name = "document_id"))
    private Set<SubjectEmbeddable> subjects = new LinkedHashSet<>();

    protected DocumentEntity() {
    }

    DocumentEntity(UUID id) {
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

    String getTitleOrigin() {
        return titleOrigin;
    }

    void setTitleOrigin(String titleOrigin) {
        this.titleOrigin = titleOrigin;
    }

    String getOriginalFilename() {
        return originalFilename;
    }

    void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    String getContentType() {
        return contentType;
    }

    void setContentType(String contentType) {
        this.contentType = contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    String getChecksumSha256() {
        return checksumSha256;
    }

    void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }

    UUID getUploadedBy() {
        return uploadedBy;
    }

    void setUploadedBy(UUID uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    Instant getUploadedAt() {
        return uploadedAt;
    }

    void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    String getStagingPath() {
        return stagingPath;
    }

    void setStagingPath(String stagingPath) {
        this.stagingPath = stagingPath;
    }

    boolean isStagingPresent() {
        return stagingPresent;
    }

    void setStagingPresent(boolean stagingPresent) {
        this.stagingPresent = stagingPresent;
    }

    String getState() {
        return state;
    }

    void setState(String state) {
        this.state = state;
    }

    int getAttempts() {
        return attempts;
    }

    void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    String getLastFailureCode() {
        return lastFailureCode;
    }

    void setLastFailureCode(String lastFailureCode) {
        this.lastFailureCode = lastFailureCode;
    }

    String getLastFailureMessage() {
        return lastFailureMessage;
    }

    void setLastFailureMessage(String lastFailureMessage) {
        this.lastFailureMessage = lastFailureMessage;
    }

    Instant getLastFailureAt() {
        return lastFailureAt;
    }

    void setLastFailureAt(Instant lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    String getPaperlessTaskId() {
        return paperlessTaskId;
    }

    void setPaperlessTaskId(String paperlessTaskId) {
        this.paperlessTaskId = paperlessTaskId;
    }

    Integer getPaperlessDocumentId() {
        return paperlessDocumentId;
    }

    void setPaperlessDocumentId(Integer paperlessDocumentId) {
        this.paperlessDocumentId = paperlessDocumentId;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    Instant getArchivedAt() {
        return archivedAt;
    }

    void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    Long getVersion() {
        return version;
    }

    Set<SubjectEmbeddable> getSubjects() {
        return subjects;
    }
}
