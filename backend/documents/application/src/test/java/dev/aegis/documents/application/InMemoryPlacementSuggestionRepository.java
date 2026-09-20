package dev.aegis.documents.application;

import dev.aegis.documents.domain.placement.PlacementSuggestion;
import dev.aegis.documents.domain.placement.PlacementSuggestionRepository;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-memory pending placement suggestions and remembered rejections for use-case tests. */
final class InMemoryPlacementSuggestionRepository implements PlacementSuggestionRepository {
    private final Map<DocumentId, PlacementSuggestion> suggestions = new LinkedHashMap<>();
    private final Map<DocumentId, Set<String>> rejections = new LinkedHashMap<>();

    @Override
    public Optional<PlacementSuggestion> findById(DocumentId id) {
        return Optional.ofNullable(suggestions.get(id));
    }

    @Override
    public Map<DocumentId, PlacementSuggestion> findAll(Collection<DocumentId> ids) {
        Map<DocumentId, PlacementSuggestion> result = new LinkedHashMap<>();
        ids.forEach(id -> findById(id).ifPresent(suggestion -> result.put(id, suggestion)));
        return result;
    }

    @Override
    public List<PlacementSuggestion> findByKey(String key) {
        return suggestions.values().stream().filter(suggestion -> suggestion.key().equals(key))
                .sorted(Comparator.comparing(PlacementSuggestion::createdAt)
                        .thenComparing(suggestion -> suggestion.documentId().value()))
                .toList();
    }

    @Override
    public Map<String, Integer> countByKeys(Collection<String> keys) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        keys.forEach(key -> counts.put(key, findByKey(key).size()));
        return counts;
    }

    @Override
    public void save(PlacementSuggestion suggestion) {
        suggestions.put(suggestion.documentId(), suggestion);
    }

    @Override
    public void delete(DocumentId id) {
        suggestions.remove(id);
    }

    @Override
    public Set<String> rejectedKeys(DocumentId id) {
        return Set.copyOf(rejections.getOrDefault(id, Set.of()));
    }

    @Override
    public Map<DocumentId, Set<String>> rejectedKeys(Collection<DocumentId> ids) {
        Map<DocumentId, Set<String>> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, rejectedKeys(id)));
        return result;
    }

    @Override
    public void reject(DocumentId id, String key, MemberId by, Instant at) {
        rejections.computeIfAbsent(id, document -> new LinkedHashSet<>()).add(key);
    }
}
