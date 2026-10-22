package dev.aegis.documents.application;

import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.text.ArchiveTextCheck;
import dev.aegis.documents.domain.text.ArchiveTextCheckRepository;
import dev.aegis.documents.domain.text.TextFingerprint;
import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory text checks with the same save and due rules as the PostgreSQL adapter. */
final class InMemoryArchiveTextCheckRepository implements ArchiveTextCheckRepository {
    final Map<DocumentId, ArchiveTextCheck> checks = new LinkedHashMap<>();
    private final DocumentRepository documents;

    InMemoryArchiveTextCheckRepository() {
        this(null);
    }

    /** {@code documents} is needed only for {@link #findDue}. */
    InMemoryArchiveTextCheckRepository(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<ArchiveTextCheck> find(DocumentId documentId) {
        return Optional.ofNullable(checks.get(documentId));
    }

    @Override
    public Map<DocumentId, ArchiveTextCheck> findAll(Collection<DocumentId> documentIds) {
        Map<DocumentId, ArchiveTextCheck> found = new LinkedHashMap<>();
        documentIds.forEach(id -> find(id).ifPresent(check -> found.put(id, check)));
        return found;
    }

    @Override
    public void save(ArchiveTextCheck check) {
        ArchiveTextCheck existing = checks.get(check.documentId());
        if (!check.isMeasured() && existing != null && existing.isMeasured()
                && existing.appliesTo(check.paperlessDocumentId())) {
            return;
        }
        checks.put(check.documentId(), check);
    }

    @Override
    public List<DocumentId> findDue(Instant now, int limit) {
        return documents.findAll().stream()
                .filter(d -> d.state() == ProcessingState.ARCHIVED && d.paperlessDocumentId().isPresent())
                .filter(d -> isDue(checks.get(d.id()), d.paperlessDocumentId().get(), now))
                .sorted(Comparator.comparing((Document d) -> backfill(checks.get(d.id()), d))
                        .thenComparing(d -> backfill(checks.get(d.id()), d) ? checks.get(d.id()).checkedAt()
                                : Instant.MIN)
                        .thenComparing(Comparator.comparing((Document d) -> d.archivedAt().orElse(Instant.MIN))
                                .reversed()))
                .limit(limit).map(Document::id).toList();
    }

    @Override
    public Map<DocumentId, TextFingerprint> findFingerprints() {
        Map<DocumentId, TextFingerprint> found = new LinkedHashMap<>();
        for (Document d : documents.findAll()) {
            ArchiveTextCheck check = checks.get(d.id());
            if (d.state() == ProcessingState.ARCHIVED && !d.isTrashed() && check != null
                    && check.fingerprint() != null && d.paperlessDocumentId().filter(check::appliesTo).isPresent()) {
                found.put(d.id(), check.fingerprint());
            }
        }
        return found;
    }

    /** A measured check of the current archive document that only lacks its fingerprint. */
    private static boolean backfill(ArchiveTextCheck check, Document d) {
        return check != null && check.isMeasured() && d.paperlessDocumentId().filter(check::appliesTo).isPresent();
    }

    private static boolean isDue(ArchiveTextCheck check, int paperlessId, Instant now) {
        return check == null || !check.appliesTo(paperlessId)
                || (!check.isMeasured() && !check.nextAttemptAt().isAfter(now))
                || (check.isMeasured() && !check.isComplete());
    }
}
