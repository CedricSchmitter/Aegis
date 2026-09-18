package dev.aegis.documents.domain;

import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Aggregate persistence for documents. Implementations apply optimistic locking on the version. */
public interface DocumentRepository {
    Optional<Document> findById(DocumentId id);

    /** Documents with the given ids in the order of {@code ids}; unknown ids are skipped. */
    List<Document> findByIds(Collection<DocumentId> ids);

    Optional<Document> findByChecksum(Checksum checksum);

    /** All documents, newest upload first. */
    List<Document> findAll();

    /** Case-insensitive title match, newest upload first. */
    List<Document> findByTitleContaining(String fragment);

    List<Document> findByPaperlessDocumentIds(List<Integer> paperlessDocumentIds);

    List<Document> findByState(ProcessingState state);

    /** Archived documents whose staging copy is still present and whose archival happened before the cut-off. */
    List<Document> findArchivedWithStagingBefore(Instant archivedBefore);

    void save(Document document);
}
