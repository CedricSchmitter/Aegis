package dev.aegis.documents.application;

import dev.aegis.documents.domain.history.DocumentChange;
import dev.aegis.documents.domain.history.DocumentHistoryRepository;
import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Assigns the sequence like the database and keeps its guarantee of at most one undo per change. */
final class InMemoryDocumentHistoryRepository implements DocumentHistoryRepository {
    static final Instant RECORDING_SINCE = Instant.parse("2026-09-01T00:00:00Z");
    final List<DocumentChange> changes = new ArrayList<>();

    @Override
    public void append(DocumentChange change) {
        if (change.undoOf() != null && changes.stream().anyMatch(c -> change.undoOf().equals(c.undoOf()))) {
            throw new IllegalStateException("change undone twice");
        }
        changes.add(new DocumentChange(change.id(), change.documentId(), changes.size() + 1L, change.occurredAt(),
                change.actor(), change.action(), change.changes(), change.undoOf()));
    }

    @Override
    public List<DocumentChange> findByDocument(DocumentId documentId, Long beforeSequence, int limit) {
        return changes.stream().filter(c -> c.documentId().equals(documentId))
                .filter(c -> beforeSequence == null || c.sequence() < beforeSequence)
                .sorted(Comparator.comparingLong(DocumentChange::sequence).reversed()).limit(limit).toList();
    }

    @Override
    public Optional<DocumentChange> find(UUID changeId) {
        return changes.stream().filter(c -> c.id().equals(changeId)).findFirst();
    }

    @Override
    public Optional<UUID> undoneBy(UUID changeId) {
        return changes.stream().filter(c -> changeId.equals(c.undoOf())).map(DocumentChange::id).findFirst();
    }

    @Override
    public Instant recordingSince() {
        return RECORDING_SINCE;
    }

    List<DocumentChange> of(DocumentId documentId) {
        return findByDocument(documentId, null, Integer.MAX_VALUE);
    }
}
