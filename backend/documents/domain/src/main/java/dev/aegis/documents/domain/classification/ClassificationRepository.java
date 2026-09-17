package dev.aegis.documents.domain.classification;

import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.DocumentId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of document classifications with optimistic version checks. */
public interface ClassificationRepository {
    Optional<DocumentClassification> findById(DocumentId id);

    /** Classifications of the given documents; documents without a stored row are absent from the map. */
    Map<DocumentId, DocumentClassification> findAll(Collection<DocumentId> ids);

    /** Classifications with any assignment (in any review state) of the label. */
    List<DocumentClassification> findByLabel(LabelId label);

    /** Classifications placed directly in one of the folders. */
    List<DocumentClassification> findByCategories(Collection<CategoryId> categories);

    void save(DocumentClassification classification);
}
