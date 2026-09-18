package dev.aegis.documents.domain.analysis;

import dev.aegis.shared.DocumentId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Persistence of document analyses with optimistic version checks. */
public interface DocumentAnalysisRepository {
    Optional<DocumentAnalysis> findById(DocumentId id);

    /** Analyses of the given documents; documents without a stored analysis are absent from the map. */
    Map<DocumentId, DocumentAnalysis> findAll(Collection<DocumentId> ids);

    /**
     * Inserts a new analysis (version 0) or updates a stored one if its version still matches.
     *
     * @throws dev.aegis.shared.StaleVersionException when another transaction changed it meanwhile
     */
    void save(DocumentAnalysis analysis);
}
