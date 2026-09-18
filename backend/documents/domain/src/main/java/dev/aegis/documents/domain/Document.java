package dev.aegis.documents.domain;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A captured household document. Aegis owns the capture record and processing state; Paperless owns the archived
 * original. The staging copy in ingress is retained until archival is verified and the retention rule permits
 * removal.
 */
public final class Document {
    public static final int MAX_TITLE_LENGTH = 200;

    private final DocumentId id;
    private String title;
    private TitleOrigin titleOrigin;
    private final String originalFilename;
    private final String contentType;
    private final long sizeBytes;
    private final Checksum checksum;
    private final MemberId uploadedBy;
    private final Instant uploadedAt;
    private final Set<Subject> subjects;
    private final String stagingPath;
    private boolean stagingPresent;

    private ProcessingState state;
    private int attempts;
    private ProcessingFailure lastFailure;
    private String paperlessTaskId;
    private Integer paperlessDocumentId;
    private Instant submittedAt;
    private Instant archivedAt;
    private long version;

    private Document(DocumentId id, String title, String originalFilename, String contentType, long sizeBytes,
            Checksum checksum, MemberId uploadedBy, Instant uploadedAt, Set<Subject> subjects, String stagingPath) {
        this.id = Objects.requireNonNull(id);
        this.title = requireTitle(title);
        this.titleOrigin = TitleOrigin.FILENAME;
        this.originalFilename = Objects.requireNonNull(originalFilename);
        this.contentType = Objects.requireNonNull(contentType);
        this.sizeBytes = sizeBytes;
        this.checksum = Objects.requireNonNull(checksum);
        this.uploadedBy = Objects.requireNonNull(uploadedBy);
        this.uploadedAt = Objects.requireNonNull(uploadedAt);
        this.subjects = new LinkedHashSet<>(subjects);
        this.stagingPath = Objects.requireNonNull(stagingPath);
        this.stagingPresent = true;
        this.state = ProcessingState.RECEIVED;
    }

    /** Records durable receipt. Only call after the original is written to ingress and checksummed. */
    public static Document received(DocumentId id, String title, String originalFilename, String contentType,
            long sizeBytes, Checksum checksum, MemberId uploadedBy, Instant now, Set<Subject> subjects,
            String stagingPath) {
        if (sizeBytes <= 0) {
            throw new InvalidInputException("document.empty", "Document must not be empty");
        }
        return new Document(id, title, originalFilename, contentType, sizeBytes, checksum, uploadedBy, now, subjects,
                stagingPath);
    }

    /** Durable receipt of a restored document whose title keeps its exported origin. */
    public static Document received(DocumentId id, String title, TitleOrigin titleOrigin, String originalFilename,
            String contentType, long sizeBytes, Checksum checksum, MemberId uploadedBy, Instant now,
            Set<Subject> subjects, String stagingPath) {
        Document document = received(id, title, originalFilename, contentType, sizeBytes, checksum, uploadedBy, now,
                subjects, stagingPath);
        document.titleOrigin = Objects.requireNonNull(titleOrigin, "title origin");
        return document;
    }

    /** Rehydration from persistence; performs no business validation. */
    public static Document rehydrate(DocumentId id, String title, String originalFilename, String contentType,
            long sizeBytes, Checksum checksum, MemberId uploadedBy, Instant uploadedAt, Set<Subject> subjects,
            String stagingPath, boolean stagingPresent, ProcessingState state, int attempts,
            ProcessingFailure lastFailure, String paperlessTaskId, Integer paperlessDocumentId, Instant submittedAt,
            Instant archivedAt, long version, TitleOrigin titleOrigin) {
        Document document = new Document(id, title, originalFilename, contentType, sizeBytes, checksum, uploadedBy,
                uploadedAt, subjects, stagingPath);
        document.stagingPresent = stagingPresent;
        document.state = state;
        document.attempts = attempts;
        document.lastFailure = lastFailure;
        document.paperlessTaskId = paperlessTaskId;
        document.paperlessDocumentId = paperlessDocumentId;
        document.submittedAt = submittedAt;
        document.archivedAt = archivedAt;
        document.version = version;
        document.titleOrigin = titleOrigin == null ? TitleOrigin.MANUAL : titleOrigin;
        return document;
    }

    /** Paperless accepted the file for consumption. */
    public void markSubmitted(String taskId, Instant now) {
        if (state == ProcessingState.ARCHIVED) {
            throw new IllegalStateException("document " + id + " is already archived");
        }
        this.paperlessTaskId = Objects.requireNonNull(taskId);
        this.submittedAt = now;
        this.state = ProcessingState.SUBMITTED;
    }

    /** Paperless finished consumption or reported an existing duplicate; either way the original is archived. */
    public void markArchived(int paperlessDocumentId, Instant now) {
        if (state == ProcessingState.ARCHIVED) {
            if (!Objects.equals(this.paperlessDocumentId, paperlessDocumentId)) {
                throw new IllegalStateException("document " + id + " already archived as " + this.paperlessDocumentId);
            }
            return;
        }
        this.paperlessDocumentId = paperlessDocumentId;
        this.archivedAt = now;
        this.lastFailure = null;
        this.state = ProcessingState.ARCHIVED;
    }

    /**
     * Records a failed attempt. Retryable failures keep the current state so the job can try again; the document
     * turns FAILED once attempts reach the limit or the failure is not retryable.
     *
     * @return true when another automatic attempt is allowed
     */
    public boolean recordFailure(ProcessingFailure failure, boolean retryable, int maxAttempts) {
        if (state == ProcessingState.ARCHIVED) {
            throw new IllegalStateException("document " + id + " is already archived");
        }
        this.attempts++;
        this.lastFailure = Objects.requireNonNull(failure);
        boolean retryAllowed = retryable && attempts < maxAttempts;
        if (!retryAllowed) {
            this.state = ProcessingState.FAILED;
        } else if (state == ProcessingState.SUBMITTED) {
            // A submission that ended in failure must be resubmitted; the old task id is no longer useful.
            this.state = ProcessingState.RECEIVED;
            this.paperlessTaskId = null;
        }
        return retryAllowed;
    }

    /** Manual retry after exhaustion. Resets the attempt budget but keeps the failure history visible. */
    public void requestRetry() {
        if (state != ProcessingState.FAILED) {
            throw new InvalidInputException("document.retry.notFailed", "Only failed documents can be retried");
        }
        this.attempts = 0;
        this.paperlessTaskId = null;
        this.state = ProcessingState.RECEIVED;
    }

    public void changeSubjects(Set<Subject> newSubjects) {
        subjects.clear();
        subjects.addAll(newSubjects);
    }

    /** A member chose the title; automatic classification never replaces it afterwards. */
    public void rename(String newTitle) {
        this.title = requireTitle(newTitle);
        this.titleOrigin = TitleOrigin.MANUAL;
    }

    /**
     * Applies a generated title while the title is still the filename-derived one the classifier analyzed. A title
     * a member chose, or one an earlier run generated, is never replaced, so reprocessing cannot keep renaming.
     *
     * @return whether the title changed
     */
    public boolean applyGeneratedTitle(String generated, String analyzedTitle) {
        if (titleOrigin != TitleOrigin.FILENAME || !title.equals(analyzedTitle)) {
            return false;
        }
        this.title = requireTitle(generated);
        this.titleOrigin = TitleOrigin.AUTOMATIC;
        return true;
    }

    /** Staging copy removed after verified archival and retention; only valid once archived. */
    public void markStagingRemoved() {
        if (state != ProcessingState.ARCHIVED) {
            throw new IllegalStateException("staging copy must be kept until document " + id + " is archived");
        }
        this.stagingPresent = false;
    }

    public DocumentId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public TitleOrigin titleOrigin() {
        return titleOrigin;
    }

    public String originalFilename() {
        return originalFilename;
    }

    public String contentType() {
        return contentType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Checksum checksum() {
        return checksum;
    }

    public MemberId uploadedBy() {
        return uploadedBy;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }

    public Set<Subject> subjects() {
        return Collections.unmodifiableSet(subjects);
    }

    public String stagingPath() {
        return stagingPath;
    }

    public boolean isStagingPresent() {
        return stagingPresent;
    }

    public ProcessingState state() {
        return state;
    }

    public int attempts() {
        return attempts;
    }

    public Optional<ProcessingFailure> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public Optional<String> paperlessTaskId() {
        return Optional.ofNullable(paperlessTaskId);
    }

    public Optional<Integer> paperlessDocumentId() {
        return Optional.ofNullable(paperlessDocumentId);
    }

    public Optional<Instant> submittedAt() {
        return Optional.ofNullable(submittedAt);
    }

    public Optional<Instant> archivedAt() {
        return Optional.ofNullable(archivedAt);
    }

    public long version() {
        return version;
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException("document.title.missing", "Title is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new InvalidInputException("document.title.tooLong", "Title exceeds " + MAX_TITLE_LENGTH);
        }
        return trimmed;
    }
}
