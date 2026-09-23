package dev.aegis.documents.application;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Test double; PostgreSQL semantics are covered by adapter integration tests. */
public class InMemoryDocumentRepository implements DocumentRepository {
    private final Map<DocumentId, Document> store = new LinkedHashMap<>();

    @Override
    public Optional<Document> findById(DocumentId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Document> findByIds(java.util.Collection<DocumentId> ids) {
        return ids.stream().map(store::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public Optional<Document> findByChecksum(Checksum checksum) {
        return store.values().stream().filter(d -> d.checksum().equals(checksum)).findFirst();
    }

    @Override
    public List<Document> findAll() {
        return store.values().stream().sorted(Comparator.comparing(Document::uploadedAt).reversed()).toList();
    }

    @Override
    public List<Document> findByTitleContaining(String fragment) {
        String needle = fragment.toLowerCase(Locale.ROOT);
        return findAll().stream().filter(d -> d.title().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    @Override
    public List<Document> findByPaperlessDocumentIds(List<Integer> paperlessDocumentIds) {
        return findAll().stream()
                .filter(d -> d.paperlessDocumentId().map(paperlessDocumentIds::contains).orElse(false)).toList();
    }

    @Override
    public List<Document> findByState(ProcessingState state) {
        return findAll().stream().filter(d -> d.state() == state).toList();
    }

    @Override
    public List<Document> findArchivedWithStagingBefore(Instant archivedBefore) {
        return findAll().stream().filter(d -> d.state() == ProcessingState.ARCHIVED && d.isStagingPresent()
                && d.archivedAt().map(at -> at.isBefore(archivedBefore)).orElse(false)).toList();
    }

    @Override
    public List<Document> findTrashed() {
        return store.values().stream().filter(Document::isTrashed)
                .sorted(Comparator.comparing((Document d) -> d.trash().orElseThrow().trashedAt()).reversed())
                .toList();
    }

    @Override
    public void save(Document document) {
        store.put(document.id(), document);
    }
}
