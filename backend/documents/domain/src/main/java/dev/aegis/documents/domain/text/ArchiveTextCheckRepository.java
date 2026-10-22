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
     * Stores the check of its document. A failed check never replaces a measurement of the same archive document,
     * also when two checks of one document race.
     */
    void save(ArchiveTextCheck check);

    /**
     * Archived documents that still need a check, most recently archived first: those without one, those whose check
     * belongs to another archive document, incomplete checks (failed, or stored before the archive version was
     * recorded) whose next attempt is due at {@code now}, and measurements without a fingerprint of the current
     * {@link TextFingerprint#VERSION}.
     */
    List<DocumentId> findDue(Instant now, int limit);

    /**
     * The fingerprints of every archived document outside the trash whose check belongs to its current archive
     * document, for comparing them with each other.
     */
    Map<DocumentId, TextFingerprint> findFingerprints();
}
