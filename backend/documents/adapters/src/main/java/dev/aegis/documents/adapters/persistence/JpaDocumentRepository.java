package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingFailure;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.SubjectKind;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
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
                e.getVersion() == null ? 0 : e.getVersion());
    }
}
