package dev.aegis.documents.application;

import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.StaleVersionException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test double; PostgreSQL semantics are covered by the integration tests. */
public class InMemoryClassificationRepository implements ClassificationRepository {
    private final Map<DocumentId, DocumentClassification> store = new LinkedHashMap<>();

    @Override
    public Optional<DocumentClassification> findById(DocumentId id) {
        return Optional.ofNullable(store.get(id)).map(InMemoryClassificationRepository::copy);
    }

    @Override
    public Map<DocumentId, DocumentClassification> findAll(Collection<DocumentId> ids) {
        Map<DocumentId, DocumentClassification> found = new LinkedHashMap<>();
        ids.forEach(id -> findById(id).ifPresent(classification -> found.put(id, classification)));
        return found;
    }

    @Override
    public List<DocumentClassification> findByLabel(LabelId label) {
        return store.values().stream().filter(c -> c.assignment(label).isPresent())
                .map(InMemoryClassificationRepository::copy).toList();
    }

    @Override
    public List<DocumentClassification> findByCategories(Collection<CategoryId> categories) {
        return store.values().stream()
                .filter(c -> c.categoryId().map(categories::contains).orElse(false))
                .map(InMemoryClassificationRepository::copy).toList();
    }

    @Override
    public void save(DocumentClassification classification) {
        DocumentClassification stored = store.get(classification.documentId());
        if (stored != null && stored.version() != classification.version()) {
            throw new StaleVersionException("documentClassification", classification.documentId().toString(),
                    classification.version(), stored.version());
        }
        store.put(classification.documentId(), rehydrate(classification, classification.version() + 1));
    }

    private static DocumentClassification copy(DocumentClassification classification) {
        return rehydrate(classification, classification.version());
    }

    private static DocumentClassification rehydrate(DocumentClassification classification, long version) {
        return DocumentClassification.rehydrate(classification.documentId(),
                classification.categoryId().orElse(null), classification.assignments(),
                classification.updatedBy().orElse(null), classification.updatedAt().orElse(null), version);
    }
}
