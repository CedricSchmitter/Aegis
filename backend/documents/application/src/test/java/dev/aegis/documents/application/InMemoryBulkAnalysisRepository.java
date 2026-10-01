package dev.aegis.documents.application;

import dev.aegis.documents.domain.bulk.BulkAnalysis;
import dev.aegis.documents.domain.bulk.BulkAnalysisRepository;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.StaleVersionException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory bulk analyses with the version rule and the one-running-run rule of the PostgreSQL adapter. */
final class InMemoryBulkAnalysisRepository implements BulkAnalysisRepository {
    private final Map<UUID, BulkAnalysis> stored = new LinkedHashMap<>();

    @Override
    public Optional<BulkAnalysis> findById(UUID id) {
        return Optional.ofNullable(stored.get(id)).map(InMemoryBulkAnalysisRepository::copy);
    }

    @Override
    public Optional<BulkAnalysis> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    @Override
    public Optional<BulkAnalysis> findRunning() {
        return stored.values().stream().filter(BulkAnalysis::running).findFirst()
                .map(InMemoryBulkAnalysisRepository::copy);
    }

    @Override
    public List<BulkAnalysis> findRecent(int limit) {
        return stored.values().stream().sorted(Comparator.comparing(BulkAnalysis::requestedAt).reversed())
                .limit(limit).map(InMemoryBulkAnalysisRepository::copy).toList();
    }

    @Override
    public void save(BulkAnalysis bulk) {
        BulkAnalysis current = stored.get(bulk.id());
        if (bulk.isNew()) {
            if (current != null || stored.values().stream().anyMatch(BulkAnalysis::running)) {
                throw new InvalidInputException("analysis.bulk.active", "Another bulk analysis is running");
            }
        } else if (current == null || current.version() != bulk.version()) {
            throw new StaleVersionException("bulkAnalysis", bulk.id().toString(), bulk.version(),
                    current == null ? -1 : current.version());
        }
        stored.put(bulk.id(), BulkAnalysis.rehydrate(bulk.id(), bulk.requestedBy(), bulk.requestedAt(), bulk.scope(),
                bulk.batchSize(), bulk.state(), bulk.stopCode().orElse(null), bulk.cancelledBy().orElse(null),
                bulk.cancelledAt().orElse(null), bulk.finishedAt().orElse(null), bulk.items(), bulk.version() + 1));
    }

    private static BulkAnalysis copy(BulkAnalysis bulk) {
        return BulkAnalysis.rehydrate(bulk.id(), bulk.requestedBy(), bulk.requestedAt(), bulk.scope(),
                bulk.batchSize(), bulk.state(), bulk.stopCode().orElse(null), bulk.cancelledBy().orElse(null),
                bulk.cancelledAt().orElse(null), bulk.finishedAt().orElse(null), bulk.items(), bulk.version());
    }
}
