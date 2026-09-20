package dev.aegis.documents.domain.placement;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pending folder placement suggestions and the placements a member rejected. Rejections are remembered per
 * document and target key so that retries and later runs do not propose the same folder or path again.
 */
public interface PlacementSuggestionRepository {

    Optional<PlacementSuggestion> findById(DocumentId id);

    Map<DocumentId, PlacementSuggestion> findAll(Collection<DocumentId> ids);

    /** Documents with a pending suggestion for the same target, oldest first; used to accept one path for all. */
    List<PlacementSuggestion> findByKey(String key);

    /** How many documents share each of the given targets; used to offer one acceptance for all of them. */
    Map<String, Integer> countByKeys(Collection<String> keys);

    /** Replaces the pending suggestion of a document. */
    void save(PlacementSuggestion suggestion);

    /** Removes the pending suggestion of a document, if any. */
    void delete(DocumentId id);

    Set<String> rejectedKeys(DocumentId id);

    Map<DocumentId, Set<String>> rejectedKeys(Collection<DocumentId> ids);

    /** Remembers that a member does not want this target for this document. */
    void reject(DocumentId id, String key, MemberId by, Instant at);
}
