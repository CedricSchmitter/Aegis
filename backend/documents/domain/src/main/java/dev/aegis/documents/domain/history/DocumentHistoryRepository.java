package dev.aegis.documents.domain.history;

import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only document history (ADR-0029). Entries are written in the transaction of the change they describe and
 * never updated; they leave only together with their document, when it is deleted permanently.
 */
public interface DocumentHistoryRepository {

    void append(DocumentChange change);

    /** Newest first; {@code beforeSequence} continues a page, null starts at the newest. */
    List<DocumentChange> findByDocument(DocumentId documentId, Long beforeSequence, int limit);

    Optional<DocumentChange> find(UUID changeId);

    /** The undo that reversed {@code changeId}, if any. */
    Optional<UUID> undoneBy(UUID changeId);

    /** When the household began recording document history; earlier changes were never recorded. */
    Instant recordingSince();
}
