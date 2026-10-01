package dev.aegis.documents.domain.bulk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of bulk analyses with their items and optimistic version checks. */
public interface BulkAnalysisRepository {
    Optional<BulkAnalysis> findById(UUID id);

    /** Loads the run and locks it until the transaction ends, so its steps and a cancellation are serialized. */
    Optional<BulkAnalysis> findByIdForUpdate(UUID id);

    /** The running bulk analysis; at most one runs at a time. */
    Optional<BulkAnalysis> findRunning();

    /** The most recently started runs first. */
    List<BulkAnalysis> findRecent(int limit);

    /**
     * Inserts a new run or updates a stored one if its version still matches, writing the changed items.
     *
     * @throws dev.aegis.shared.StaleVersionException when another transaction changed it meanwhile
     * @throws dev.aegis.shared.InvalidInputException {@code analysis.bulk.active} when another run is already running
     */
    void save(BulkAnalysis bulk);
}
