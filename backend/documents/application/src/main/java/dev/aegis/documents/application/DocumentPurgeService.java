package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.DocumentEvents;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.DocumentId;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * State transitions of a document's permanent deletion (ADR-0021); every method is one short transaction. The purge
 * job calls them around its archive and file system work and acts for the member who requested the deletion.
 */
public class DocumentPurgeService implements PurgeUseCases {
    private final DocumentRepository documents;
    private final DocumentEvents events;
    private final ArchivalSettings settings;
    private final Clock clock;

    public DocumentPurgeService(DocumentRepository documents, DocumentEvents events, ArchivalSettings settings,
            Clock clock) {
        this.documents = documents;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public PurgeStep prepare(DocumentId id) {
        Optional<Document> found = documents.findById(id);
        if (found.isEmpty()) {
            return new Finished(id, "record gone");
        }
        Document document = found.get();
        if (!document.isPurgePending()) {
            return new Finished(id, document.purge().isPresent() ? "purge failed; waiting for a member"
                    : "no purge requested");
        }
        Integer paperlessId = document.paperlessDocumentId()
                .filter(archiveId -> !archiveDocumentShared(id, archiveId)).orElse(null);
        String unrecordedTask = document.state() == ProcessingState.FAILED && document.paperlessDocumentId().isEmpty()
                ? document.paperlessTaskId().orElse(null) : null;
        return new Purge(id, paperlessId, unrecordedTask, document.stagingPath());
    }

    @Override
    public boolean archiveDocumentShared(DocumentId id, int paperlessDocumentId) {
        List<Document> users = documents.findByPaperlessDocumentIds(List.of(paperlessDocumentId));
        return users.stream().anyMatch(other -> !other.id().equals(id));
    }

    @Override
    public FailureDecision recordFailure(DocumentId id, String code, boolean retryable) {
        Document document = documents.findById(id).orElse(null);
        if (document == null || !document.isPurgePending()) {
            return new FailureDecision(false, 0);
        }
        boolean retry = document.recordPurgeFailure(code, clock.instant(), retryable, settings.maxAttempts());
        documents.save(document);
        return new FailureDecision(retry, document.purge().orElseThrow().attempts());
    }

    @Override
    public void complete(DocumentId id) {
        Document document = documents.findById(id).orElse(null);
        if (document == null || !document.isPurgePending()) {
            return;
        }
        documents.purge(document, clock.instant());
        events.documentPurged(id);
    }
}
