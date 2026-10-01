package dev.aegis.documents.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.documents.application.ArchivalSettings;
import dev.aegis.documents.application.PurgeUseCases;
import dev.aegis.documents.application.PurgeUseCases.FailureDecision;
import dev.aegis.documents.application.PurgeUseCases.Finished;
import dev.aegis.documents.application.PurgeUseCases.Purge;
import dev.aegis.documents.application.PurgeUseCases.PurgeStep;
import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.PaperlessArchive.Completed;
import dev.aegis.documents.application.ports.PaperlessArchive.Pending;
import dev.aegis.shared.DocumentId;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable permanent deletion of a trashed document (ADR-0021). It deletes the archive original including its entry in
 * the archive's own trash, then the staging copy, and only then the record, so a crash at any point leaves a pending
 * purge that the next execution finishes. Every step is safe to repeat: the archive counts a document it no longer
 * holds as deleted, and a missing staging file is no error. An uncertain archive outcome is retried with a bound; a
 * refusal or the last failed attempt marks the purge failed for a member to request again.
 */
@Configuration
public class PurgeDocumentTask {
    public static final TaskDescriptor<PurgeDocumentPayload> DESCRIPTOR = TaskDescriptor.of("purge-document",
            PurgeDocumentPayload.class);
    private static final Logger log = LoggerFactory.getLogger(PurgeDocumentTask.class);

    private final PurgeUseCases purges;
    private final PaperlessArchive archive;
    private final OriginalStore store;
    private final ArchivalSettings settings;
    private final Clock clock;

    public PurgeDocumentTask(PurgeUseCases purges, PaperlessArchive archive, OriginalStore store,
            ArchivalSettings settings, Clock clock) {
        this.purges = purges;
        this.archive = archive;
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    @Bean
    CustomTask<PurgeDocumentPayload> purgeDocumentJob() {
        return Tasks.custom(DESCRIPTOR)
                // Unexpected exceptions (bugs, database outage) are retried by the scheduler; archive and file
                // failures are recorded below and never thrown.
                .onFailureReschedule(FixedDelay.ofMinutes(5))
                .onDeadExecutionRevive()
                .execute(this::run);
    }

    CompletionHandler<PurgeDocumentPayload> run(TaskInstance<PurgeDocumentPayload> instance,
            ExecutionContext context) {
        DocumentId id = DocumentId.of(instance.getData().documentId());
        PurgeStep step = purges.prepare(id);
        if (step instanceof Finished finished) {
            log.debug("Purge job for {} finished: {}", id, finished.reason());
            return stop();
        }
        Purge purge = (Purge) step;
        try {
            Integer archiveId = purge.paperlessDocumentId();
            if (archiveId == null && purge.unrecordedTaskId() != null) {
                PaperlessArchive.TaskOutcome outcome = archive.checkTask(purge.unrecordedTaskId());
                if (outcome instanceof Pending) {
                    // The archive may still create the document; deleting the record now would orphan it there.
                    return afterFailure(purges.recordFailure(id, "paperless.consumptionPending", true));
                }
                if (outcome instanceof Completed completed
                        && !purges.archiveDocumentShared(id, completed.paperlessDocumentId())) {
                    archiveId = completed.paperlessDocumentId();
                }
            }
            if (archiveId != null) {
                archive.purge(archiveId);
            }
            store.delete(purge.stagingPath());
            purges.complete(id);
            log.info("Document {} deleted permanently", id);
            return stop();
        } catch (ArchiveUnavailableException unavailable) {
            log.warn("Archive unavailable while deleting {} permanently: {}", id, unavailable.getMessage());
            return afterFailure(purges.recordFailure(id, "paperless.unavailable", true));
        } catch (ArchiveRejectedException rejected) {
            log.warn("Archive refused the permanent deletion of {}: {}", id, rejected.getMessage());
            return afterFailure(purges.recordFailure(id, rejected.code(), false));
        } catch (IOException e) {
            log.warn("Could not delete the staging copy of {}: {}", id, e.getMessage());
            return afterFailure(purges.recordFailure(id, "document.stagingDeleteFailed", true));
        }
    }

    private CompletionHandler<PurgeDocumentPayload> afterFailure(FailureDecision decision) {
        if (!decision.retry()) {
            return stop();
        }
        Instant next = clock.instant().plus(ArchiveDocumentTask.backoff(decision.attempts(), settings.retryBase()));
        return (complete, operations) -> operations.reschedule(complete, next);
    }

    private static CompletionHandler<PurgeDocumentPayload> stop() {
        return (complete, operations) -> operations.stop();
    }
}
