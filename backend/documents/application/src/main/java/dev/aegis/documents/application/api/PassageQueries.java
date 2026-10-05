package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.List;

/** Authorized lexical retrieval. Completeness describes technical coverage, never semantic recall. */
public interface PassageQueries {
    enum State { CURRENT, PENDING, FAILED, STALE, NO_TEXT, SOURCE_UNAVAILABLE }
    record Coverage(long total, long current, long pending, long failed, long stale, long noText,
            long sourceUnavailable, Instant oldestVerifiedAt, Instant generatedAt, long freshnessSeconds) {
        public boolean complete() { return total == current; }
    }
    record Passage(DocumentId documentId, String title, String revision, String sourceFingerprint, String sourceKind,
            String extractionVersion, String chunkVersion, int ordinal, String original,
            int start, int end, Integer startPage, Integer endPage, Instant verifiedAt) { }
    record Page(List<Passage> passages, String nextCursor, Coverage coverage) {
        public Page { passages = List.copyOf(passages); }
    }
    record Status(DocumentId documentId, State state, String revision, String sourceKind, Instant verifiedAt,
            int attempts, Instant nextAttemptAt, String failureCode) { }
    Page search(Actor actor, String query, String cursor, int limit);
    Passage read(Actor actor, DocumentId documentId, String revision, int ordinal);
    Coverage coverage(Actor actor);
    Status status(Actor actor, DocumentId documentId);
}
