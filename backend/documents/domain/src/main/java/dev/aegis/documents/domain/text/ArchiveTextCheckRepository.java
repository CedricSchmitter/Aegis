package dev.aegis.documents.domain.text;

import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of the derived text checks; at most one per document. */
public interface ArchiveTextCheckRepository {
    Optional<ArchiveTextCheck> find(DocumentId documentId);

    Map<DocumentId, ArchiveTextCheck> findAll(Collection<DocumentId> documentIds);

    /**
     * Stores the check of its document. A failed check never replaces a count measured for the same archive
     * document, also when two checks of one document race.
     */
    void save(ArchiveTextCheck check);

    /**
     * Archived documents that still need a check, most recently archived first: those without one, those whose check
     * belongs to another archive document, and failed checks whose next attempt is due at {@code now}.
     */
    List<DocumentId> findDue(Instant now, int limit);
}
