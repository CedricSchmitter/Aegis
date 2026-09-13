package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.DocumentEvents;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingFailure;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.DocumentId;
import java.time.Clock;
import java.util.Optional;

/** State transitions of the archival process; every method is one short transaction. */
public class ArchivalService implements ArchivalUseCases {
    private final DocumentRepository documents;
    private final DocumentEvents events;
    private final ArchivalSettings settings;
    private final Clock clock;

    public ArchivalService(DocumentRepository documents, DocumentEvents events, ArchivalSettings settings,
            Clock clock) {
        this.documents = documents;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public ArchivalStep prepare(DocumentId id) {
        Optional<Document> found = documents.findById(id);
        if (found.isEmpty()) {
            return new Finished(id, "record missing");
        }
        Document document = found.get();
        return switch (document.state()) {
            case RECEIVED -> new Submit(id, document.stagingPath(), document.title(), document.originalFilename());
            case SUBMITTED -> new Poll(id, document.paperlessTaskId().orElseThrow(),
                    document.submittedAt().orElse(document.uploadedAt()));
            case ARCHIVED -> new Finished(id, "archived");
            case FAILED -> new Finished(id, "failed; waiting for manual retry");
        };
    }

    @Override
    public void recordSubmission(DocumentId id, String taskId) {
        Document document = load(id);
        if (document.state() == ProcessingState.ARCHIVED) {
            return;
        }
        document.markSubmitted(taskId, clock.instant());
        documents.save(document);
    }

    @Override
    public void recordArchived(DocumentId id, int paperlessDocumentId) {
        Document document = load(id);
        boolean alreadyArchived = document.state() == ProcessingState.ARCHIVED;
        document.markArchived(paperlessDocumentId, clock.instant());
        documents.save(document);
        if (!alreadyArchived) {
            events.documentArchived(id, document.title(), document.uploadedBy());
        }
    }

    @Override
    public FailureDecision recordFailure(DocumentId id, String code, String message, boolean retryable) {
        Document document = load(id);
        if (document.state() == ProcessingState.ARCHIVED) {
            return new FailureDecision(false, document.attempts());
        }
        boolean retry = document.recordFailure(new ProcessingFailure(code, message, clock.instant()), retryable,
                settings.maxAttempts());
        documents.save(document);
        if (!retry) {
            events.documentArchivalFailed(id, document.title(), document.uploadedBy(), code);
        }
        return new FailureDecision(retry, document.attempts());
    }

    private Document load(DocumentId id) {
        return documents.findById(id)
                .orElseThrow(() -> new IllegalStateException("document " + id + " vanished during archival"));
    }
}
