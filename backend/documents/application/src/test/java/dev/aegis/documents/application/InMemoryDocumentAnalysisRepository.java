package dev.aegis.documents.application;

import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.StaleVersionException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory analyses with the same optimistic version rule as the PostgreSQL adapter. */
final class InMemoryDocumentAnalysisRepository implements DocumentAnalysisRepository {
    private final Map<DocumentId, DocumentAnalysis> stored = new HashMap<>();

    @Override
    public Optional<DocumentAnalysis> findById(DocumentId id) {
        return Optional.ofNullable(stored.get(id)).map(InMemoryDocumentAnalysisRepository::copy);
    }

    @Override
    public Map<DocumentId, DocumentAnalysis> findAll(Collection<DocumentId> ids) {
        Map<DocumentId, DocumentAnalysis> result = new LinkedHashMap<>();
        ids.forEach(id -> findById(id).ifPresent(analysis -> result.put(id, analysis)));
        return result;
    }

    @Override
    public void save(DocumentAnalysis analysis) {
        DocumentAnalysis current = stored.get(analysis.documentId());
        long expected = current == null ? 0 : current.version();
        if (analysis.version() != expected) {
            throw new StaleVersionException("documentAnalysis", analysis.documentId().toString(), analysis.version(),
                    expected);
        }
        stored.put(analysis.documentId(), DocumentAnalysis.rehydrate(analysis.documentId(), analysis.snapshot(),
                expected + 1));
    }

    private static DocumentAnalysis copy(DocumentAnalysis analysis) {
        return DocumentAnalysis.rehydrate(analysis.documentId(), analysis.snapshot(), analysis.version());
    }
}
