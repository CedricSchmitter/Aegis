package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Closes the gaps between filesystem, database and scheduler: re-schedules pending documents without a job,
 * quarantines files without a record, and removes staging copies only after verified archival plus retention.
 */
public class ReconciliationService {
    public static final Duration ORPHAN_GRACE = Duration.ofHours(1);

    public record ReconciliationReport(int rescheduled, int quarantined, int stagingRemoved, List<String> notes) {
    }

    private final DocumentRepository documents;
    private final OriginalStore store;
    private final PaperlessArchive archive;
    private final ProcessingScheduler scheduler;
    private final ArchivalSettings settings;
    private final Clock clock;

    public ReconciliationService(DocumentRepository documents, OriginalStore store, PaperlessArchive archive,
            ProcessingScheduler scheduler, ArchivalSettings settings, Clock clock) {
        this.documents = documents;
        this.store = store;
        this.archive = archive;
        this.scheduler = scheduler;
        this.settings = settings;
        this.clock = clock;
    }

    public ReconciliationReport reconcile() throws IOException {
        List<String> notes = new java.util.ArrayList<>();
        int rescheduled = rescheduleMissingJobs();
        int quarantined = quarantineOrphans(notes);
        int removed = removeExpiredStagingCopies(notes);
        return new ReconciliationReport(rescheduled, quarantined, removed, notes);
    }

    private int rescheduleMissingJobs() {
        int count = 0;
        for (ProcessingState state : List.of(ProcessingState.RECEIVED, ProcessingState.SUBMITTED)) {
            for (Document document : documents.findByState(state)) {
                if (scheduler.nextAttempt(document.id()).isEmpty()) {
                    scheduler.scheduleArchival(document.id());
                    count++;
                }
            }
        }
        return count;
    }

    private int quarantineOrphans(List<String> notes) throws IOException {
        Set<String> known = new HashSet<>();
        documents.findAll().forEach(d -> known.add(d.stagingPath()));
        Instant cutoff = clock.instant().minus(ORPHAN_GRACE);
        int count = 0;
        for (String path : store.listStagingPaths()) {
            if (known.contains(path)) {
                continue;
            }
            if (store.resolve(path).toFile().lastModified() > cutoff.toEpochMilli()) {
                continue; // an upload may still be in flight
            }
            store.quarantineOrphan(path);
            notes.add("quarantined orphan " + path);
            count++;
        }
        return count;
    }

    private int removeExpiredStagingCopies(List<String> notes) {
        Instant cutoff = clock.instant().minus(settings.stagingRetention());
        int count = 0;
        for (Document document : documents.findArchivedWithStagingBefore(cutoff)) {
            int paperlessId = document.paperlessDocumentId().orElseThrow();
            try {
                if (!archive.exists(paperlessId)) {
                    notes.add("kept staging copy of " + document.id() + ": archive no longer lists " + paperlessId);
                    continue;
                }
            } catch (ArchiveUnavailableException unavailable) {
                notes.add("kept staging copy of " + document.id() + ": archive unavailable");
                continue;
            }
            try {
                store.delete(document.stagingPath());
            } catch (IOException e) {
                notes.add("could not delete staging copy of " + document.id() + ": " + e.getMessage());
                continue;
            }
            document.markStagingRemoved();
            documents.save(document);
            count++;
        }
        return count;
    }
}
