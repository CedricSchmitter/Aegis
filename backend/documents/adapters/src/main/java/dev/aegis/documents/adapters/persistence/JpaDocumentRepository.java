package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentPurge;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingFailure;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.SubjectKind;
import dev.aegis.documents.domain.TitleOrigin;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.Trash;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Domain repository over JPA with explicit mapping and optimistic locking translated to the shared exception. */
@Component
public class JpaDocumentRepository implements DocumentRepository {
    private final DocumentJpaRepository jpa;

    JpaDocumentRepository(DocumentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Document> findById(DocumentId id) {
        return jpa.findById(id.value()).map(JpaDocumentRepository::toDomain);
    }

    @Override
    public List<Document> findByIds(Collection<DocumentId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Document> found = new LinkedHashMap<>();
        jpa.findByIdIn(ids.stream().map(DocumentId::value).toList())
                .forEach(entity -> found.put(entity.getId(), toDomain(entity)));
        // Keeps the order the read model determined (relevance, date or title), not the database order.
        return ids.stream().map(id -> found.get(id.value())).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public Optional<Document> findByChecksum(Checksum checksum) {
        return jpa.findByChecksumSha256(checksum.sha256Hex()).map(JpaDocumentRepository::toDomain);
    }

    @Override
    public List<Document> findAll() {
        return jpa.findAllByOrderByUploadedAtDesc().stream().map(JpaDocumentRepository::toDomain).toList();
    }

    @Override
    public List<Document> findByTitleContaining(String fragment) {
        return jpa.searchTitle(fragment).stream().map(JpaDocumentRepository::toDomain).toList();
    }

    @Override
    public List<Document> findByPaperlessDocumentIds(List<Integer> paperlessDocumentIds) {
        if (paperlessDocumentIds.isEmpty()) {
            return List.of();
        }
        return jpa.findByPaperlessDocumentIdInOrderByUploadedAtDesc(paperlessDocumentIds).stream()
                .map(JpaDocumentRepository::toDomain).toList();
    }

    @Override
    public List<Document> findByState(ProcessingState state) {
        return jpa.findByStateOrderByUploadedAtDesc(state.name()).stream().map(JpaDocumentRepository::toDomain)
                .toList();
    }

    @Override
    public List<Document> findArchivedWithStagingBefore(Instant archivedBefore) {
        return jpa.findByStateAndStagingPresentTrueAndArchivedAtBefore(ProcessingState.ARCHIVED.name(),
                archivedBefore).stream().map(JpaDocumentRepository::toDomain).toList();
    }

    @Override
    public List<Document> findTrashed() {
        return jpa.findByTrashedAtIsNotNullOrderByTrashedAtDesc().stream().map(JpaDocumentRepository::toDomain)
                .toList();
    }

    @Override
    public List<Document> findPurgePending() {
        return jpa.findByPurgeRequestedAtIsNotNullAndPurgeFailedAtIsNullOrderByPurgeRequestedAtAsc().stream()
                .map(JpaDocumentRepository::toDomain).toList();
    }

    /**
     * Deleting the row cascades in the database to subjects, classification, labels, analysis, placement and text
     * checks (ADR-0021); the version check guards against a concurrent change since the job loaded the document.
     */
    @Override
    public void purge(Document document, Instant purgedAt) {
        DocumentPurge requested = document.purge().orElseThrow(() -> new IllegalStateException(
                "document " + document.id() + " has no purge request"));
        DocumentEntity entity = jpa.findById(document.id().value()).orElse(null);
        if (entity == null) {
            return;
        }
        if (entity.getVersion() != null && entity.getVersion() != document.version()) {
            throw new StaleVersionException("document", document.id().toString(), document.version(),
                    entity.getVersion());
        }
        jpa.recordPurge(document.id().value(), requested.requestedBy().value(), requested.requestedAt(), purgedAt);
        jpa.delete(entity);
        jpa.flush();
    }

    @Override
    public boolean wasPurged(DocumentId id) {
        return jpa.existsPurge(id.value());
    }

    @Override
    public void save(Document document) {
        DocumentEntity entity = jpa.findById(document.id().value()).orElseGet(() -> new DocumentEntity(
                document.id().value()));
        if (entity.getVersion() != null && entity.getVersion() != document.version()) {
            throw new StaleVersionException("document", document.id().toString(), document.version(),
                    entity.getVersion());
        }
        copy(document, entity);
        try {
            jpa.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException e) {
            throw new StaleVersionException("document", document.id().toString(), document.version(), -1);
        }
    }

    private static void copy(Document document, DocumentEntity entity) {
        entity.setTitle(document.title());
        entity.setTitleOrigin(document.titleOrigin().name());
        entity.setOriginalFilename(document.originalFilename());
        entity.setContentType(document.contentType());
        entity.setSizeBytes(document.sizeBytes());
        entity.setChecksumSha256(document.checksum().sha256Hex());
        entity.setUploadedBy(document.uploadedBy().value());
        entity.setUploadedAt(document.uploadedAt());
        entity.setStagingPath(document.stagingPath());
        entity.setStagingPresent(document.isStagingPresent());
        entity.setState(document.state().name());
        entity.setAttempts(document.attempts());
        entity.setLastFailureCode(document.lastFailure().map(ProcessingFailure::code).orElse(null));
        entity.setLastFailureMessage(document.lastFailure().map(ProcessingFailure::message).orElse(null));
        entity.setLastFailureAt(document.lastFailure().map(ProcessingFailure::at).orElse(null));
        entity.setPaperlessTaskId(document.paperlessTaskId().orElse(null));
        entity.setPaperlessDocumentId(document.paperlessDocumentId().orElse(null));
        entity.setSubmittedAt(document.submittedAt().orElse(null));
        entity.setArchivedAt(document.archivedAt().orElse(null));
        Trash trash = document.trash().orElse(null);
        entity.setTrash(trash == null ? null : trash.trashedBy().value(), trash == null ? null : trash.trashedAt(),
                trash == null ? null : trash.restorableUntil());
        DocumentPurge purge = document.purge().orElse(null);
        entity.setPurge(purge == null ? null : purge.requestedBy().value(), purge == null ? null
                : purge.requestedAt(), purge == null ? 0 : purge.attempts(), purge == null ? null
                : purge.failureCode(), purge == null ? null : purge.failedAt());
        Set<SubjectEmbeddable> subjects = new LinkedHashSet<>();
        for (Subject subject : document.subjects()) {
            subjects.add(new SubjectEmbeddable(subject.kind().name(),
                    subject.memberId().map(MemberId::value).orElse(null)));
        }
        entity.getSubjects().clear();
        entity.getSubjects().addAll(subjects);
    }

    private static Document toDomain(DocumentEntity e) {
        Set<Subject> subjects = new LinkedHashSet<>();
        for (SubjectEmbeddable s : e.getSubjects()) {
            subjects.add(new Subject(SubjectKind.valueOf(s.getKind()),
                    s.getMemberId() == null ? null : new MemberId(s.getMemberId())));
        }
        ProcessingFailure failure = e.getLastFailureCode() == null ? null
                : new ProcessingFailure(e.getLastFailureCode(), e.getLastFailureMessage(), e.getLastFailureAt());
        return Document.rehydrate(new DocumentId(e.getId()), e.getTitle(), e.getOriginalFilename(),
                e.getContentType(), e.getSizeBytes(), new Checksum(e.getChecksumSha256()),
                new MemberId(e.getUploadedBy()), e.getUploadedAt(), subjects, e.getStagingPath(),
                e.isStagingPresent(), ProcessingState.valueOf(e.getState()), e.getAttempts(), failure,
                e.getPaperlessTaskId(), e.getPaperlessDocumentId(), e.getSubmittedAt(), e.getArchivedAt(),
                e.getVersion() == null ? 0 : e.getVersion(),
                e.getTitleOrigin() == null ? TitleOrigin.MANUAL : TitleOrigin.valueOf(e.getTitleOrigin()),
                e.getTrashedAt() == null ? null : new Trash(new MemberId(e.getTrashedBy()), e.getTrashedAt(),
                        e.getRestorableUntil()),
                e.getPurgeRequestedAt() == null ? null : new DocumentPurge(new MemberId(e.getPurgeRequestedBy()),
                        e.getPurgeRequestedAt(), e.getPurgeAttempts(), e.getPurgeFailureCode(),
                        e.getPurgeFailedAt()));
    }
}
