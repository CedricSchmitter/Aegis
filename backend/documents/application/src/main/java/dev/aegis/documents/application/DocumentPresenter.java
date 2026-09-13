package dev.aegis.documents.application;

import dev.aegis.documents.application.api.DocumentExportRecord;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.ProcessingInfo;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.ProcessingState;
import java.time.Instant;
import java.util.Optional;

/** Builds read models from aggregates, including truthful retry information from the scheduler. */
public class DocumentPresenter {
    private final ProcessingScheduler scheduler;

    public DocumentPresenter(ProcessingScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public DocumentSummary summarize(Document document) {
        boolean pending = document.state() == ProcessingState.RECEIVED || document.state() == ProcessingState.SUBMITTED;
        Optional<Instant> next = pending ? scheduler.nextAttempt(document.id()) : Optional.empty();
        var failure = document.lastFailure();
        ProcessingInfo processing = new ProcessingInfo(document.state().name(), document.attempts(),
                failure.map(f -> f.code()).orElse(null), failure.map(f -> f.message()).orElse(null),
                failure.map(f -> f.at()).orElse(null), next.isPresent(), next.orElse(null),
                document.paperlessDocumentId().orElse(null), document.archivedAt().orElse(null));
        boolean originalAvailable = document.isStagingPresent() || document.state() == ProcessingState.ARCHIVED;
        return new DocumentSummary(document.id(), document.title(), document.originalFilename(),
                document.contentType(), document.sizeBytes(), document.checksum().sha256Hex(), document.uploadedBy(),
                document.uploadedAt(), Subjects.toViews(document.subjects()), processing, originalAvailable,
                document.version());
    }

    public DocumentExportRecord exportRecord(Document document) {
        return new DocumentExportRecord(document.id(), document.title(), document.originalFilename(),
                document.contentType(), document.sizeBytes(), document.checksum().sha256Hex(), document.uploadedBy(),
                document.uploadedAt(), Subjects.toViews(document.subjects()), document.state().name(),
                document.paperlessDocumentId().orElse(null), document.archivedAt().orElse(null));
    }
}
