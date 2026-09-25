package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.text.ArchiveTextCheck;
import dev.aegis.documents.domain.text.ArchiveTextCheckRepository;
import dev.aegis.shared.DocumentId;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Records whether the archive recognized text in archived documents (issue #87, ADR-0012) and whether it keeps a
 * searchable archive version of them (ADR-0016). The archival job asks right after it recorded a document as archived;
 * a recurring sweep catches up on documents archived before this existed, on checks stored before the archive version
 * was recorded, and on checks that failed. The archive is read outside any transaction and each result is one write of
 * derived data, so a check never delays, fails or rolls back archival, and repeating one is harmless.
 */
public class ArchiveTextService {
    /** What one check did. */
    public enum Outcome {
        MEASURED,
        /** A measurement of the current archive document already existed; the archive was not asked again. */
        ALREADY_MEASURED,
        FAILED,
        /** The document is missing or not archived; there is no archive text to check. */
        NOT_ARCHIVED
    }

    public record SweepReport(int measured, int failed) {
    }

    private final DocumentRepository documents;
    private final ArchiveTextCheckRepository checks;
    private final PaperlessArchive archive;
    private final int batchSize;
    private final Clock clock;

    public ArchiveTextService(DocumentRepository documents, ArchiveTextCheckRepository checks,
            PaperlessArchive archive, int batchSize, Clock clock) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("the text check batch size must be positive");
        }
        this.documents = documents;
        this.checks = checks;
        this.archive = archive;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /** Checks one document; an archive that cannot deliver the text is recorded as a failed attempt, never thrown. */
    public Outcome check(DocumentId id) {
        Optional<Document> found = documents.findById(id);
        if (found.isEmpty() || found.get().state() != ProcessingState.ARCHIVED
                || found.get().paperlessDocumentId().isEmpty()) {
            return Outcome.NOT_ARCHIVED;
        }
        int paperlessId = found.get().paperlessDocumentId().get();
        Optional<ArchiveTextCheck> previous = checks.find(id);
        if (previous.filter(check -> check.appliesTo(paperlessId) && check.isMeasured()).isPresent()) {
            return Outcome.ALREADY_MEASURED;
        }
        PaperlessArchive.ArchivedText archived;
        try {
            archived = archive.fetchArchivedText(paperlessId);
        } catch (ArchiveUnavailableException | ArchiveRejectedException unavailable) {
            checks.save(ArchiveTextCheck.failed(id, paperlessId, previous, clock.instant()));
            return Outcome.FAILED;
        }
        checks.save(ArchiveTextCheck.measured(id, paperlessId, archived.content(), archived.archiveVersion(),
                clock.instant()));
        return Outcome.MEASURED;
    }

    /**
     * Checks the next due documents, most recently archived first, at most one batch per call. It stops at the first
     * failure, so an unreachable archive is asked once per sweep rather than once per document; the failed document
     * waits for its next attempt and the others follow in the next sweep. Progress lives in the stored checks, so an
     * interrupted sweep simply continues.
     */
    public SweepReport sweep() {
        List<DocumentId> due = checks.findDue(clock.instant(), batchSize);
        int measured = 0;
        for (DocumentId id : due) {
            switch (check(id)) {
                case MEASURED -> measured++;
                case FAILED -> {
                    return new SweepReport(measured, 1);
                }
                case ALREADY_MEASURED, NOT_ARCHIVED -> {
                }
            }
        }
        return new SweepReport(measured, 0);
    }
}
