package dev.aegis.documents.application.ports;

import dev.aegis.documents.application.api.PassageQueries;
import dev.aegis.documents.domain.text.PassageRevision;
import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Documents-owned durable queue and derived revisions. Claim/publication/failure each use a short transaction. */
public interface PassageIndex {
    record Claim(DocumentId id, int archiveId, String checksum, long documentVersion, long generation,
            UUID lease, int attempts) { }
    void request(DocumentId id, Instant now);
    void invalidate(DocumentId id, Instant now);
    void suspend(DocumentId id);
    void rebuild(Instant now);
    int backfill(Instant now, int limit);
    Optional<Claim> claim(Instant now);
    boolean publish(Claim claim, PassageRevision revision, Instant now);
    void fail(Claim claim, String code, PassageQueries.State state, Instant now);
    PassageQueries.Coverage coverage(Instant now);
    PassageQueries.Status status(DocumentId id, Instant now);
    PassageQueries.Page search(String query, String cursor, int limit, Instant now);
    Optional<PassageQueries.Passage> read(DocumentId id, String revision, int ordinal, Instant now);
}
