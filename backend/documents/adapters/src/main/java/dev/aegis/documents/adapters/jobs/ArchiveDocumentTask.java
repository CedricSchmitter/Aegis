package dev.aegis.documents.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.documents.application.ArchivalSettings;
import dev.aegis.documents.application.ArchivalUseCases;
import dev.aegis.documents.application.ArchivalUseCases.ArchivalStep;
import dev.aegis.documents.application.ArchivalUseCases.FailureDecision;
import dev.aegis.documents.application.ArchivalUseCases.Finished;
import dev.aegis.documents.application.ArchivalUseCases.Poll;
import dev.aegis.documents.application.ArchivalUseCases.Submit;
import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.PaperlessArchive.Accepted;
import dev.aegis.documents.application.ports.PaperlessArchive.Completed;
import dev.aegis.documents.application.ports.PaperlessArchive.Duplicate;
import dev.aegis.documents.application.ports.PaperlessArchive.Failed;
import dev.aegis.documents.application.ports.PaperlessArchive.Pending;
import dev.aegis.documents.application.ports.PaperlessArchive.Rejected;
import dev.aegis.shared.DocumentId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable archival job. Each execution performs at most one archive round-trip outside any database transaction and
 * records the outcome through short transactional use cases. Repeated execution is safe: the use cases decide from
 * the persisted state what to do next.
 */
@Configuration
public class ArchiveDocumentTask {
    public static final TaskDescriptor<ArchiveDocumentPayload> DESCRIPTOR = TaskDescriptor.of("archive-document",
            ArchiveDocumentPayload.class);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    static final Duration RETRY_BASE = Duration.ofSeconds(30);
    static final Duration RETRY_MAX = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(ArchiveDocumentTask.class);

    private final ArchivalUseCases archival;
    private final PaperlessArchive archive;
    private final OriginalStore store;
    private final ArchivalSettings settings;
    private final Clock clock;

    public ArchiveDocumentTask(ArchivalUseCases archival, PaperlessArchive archive, OriginalStore store,
            ArchivalSettings settings, Clock clock) {
        this.archival = archival;
        this.archive = archive;
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    @Bean
    CustomTask<ArchiveDocumentPayload> archiveDocumentJob() {
        return Tasks.custom(DESCRIPTOR)
                // Unexpected exceptions (bugs, database outage) are retried by the scheduler; business failures are
                // handled below and never thrown.
                .onFailureReschedule(FixedDelay.ofMinutes(5))
                .onDeadExecutionRevive()
                .execute(this::run);
    }

    CompletionHandler<ArchiveDocumentPayload> run(TaskInstance<ArchiveDocumentPayload> instance,
            com.github.kagkarlsson.scheduler.task.ExecutionContext context) {
        DocumentId id = DocumentId.of(instance.getData().documentId());
        ArchivalStep step = archival.prepare(id);
        try {
            return switch (step) {
                case Finished finished -> {
                    log.debug("Archival job for {} finished: {}", id, finished.reason());
                    yield remove();
                }
                case Submit submit -> submit(submit);
                case Poll poll -> poll(poll);
            };
        } catch (ArchiveUnavailableException unavailable) {
            log.warn("Archive unavailable while processing {}: {}", id, unavailable.getMessage());
            return afterFailure(archival.recordFailure(id, "paperless.unavailable", unavailable.getMessage(), true));
        } catch (ArchiveRejectedException rejected) {
            log.warn("Archive rejected document {}: {}", id, rejected.getMessage());
            return afterFailure(archival.recordFailure(id, rejected.code(), rejected.getMessage(), false));
        }
    }

    private CompletionHandler<ArchiveDocumentPayload> submit(Submit submit) {
        if (!store.exists(submit.stagingPath())) {
            return afterFailure(archival.recordFailure(submit.id(), "document.stagingMissing",
                    "staging copy " + submit.stagingPath() + " is missing", false));
        }
        PaperlessArchive.SubmissionOutcome outcome = archive.submit(store.resolve(submit.stagingPath()),
                submit.title(), submit.originalFilename());
        return switch (outcome) {
            case Accepted accepted -> {
                archival.recordSubmission(submit.id(), accepted.taskId());
                yield rescheduleIn(POLL_INTERVAL);
            }
            case Rejected rejected -> afterFailure(archival.recordFailure(submit.id(), rejected.code(),
                    rejected.message(), false));
        };
    }

    private CompletionHandler<ArchiveDocumentPayload> poll(Poll poll) {
        if (Duration.between(poll.submittedAt(), clock.instant()).compareTo(settings.pollTimeout()) > 0) {
            return afterFailure(archival.recordFailure(poll.id(), "paperless.timeout",
                    "no consumption result within " + settings.pollTimeout(), true));
        }
        PaperlessArchive.TaskOutcome outcome = archive.checkTask(poll.taskId());
        return switch (outcome) {
            case Pending pending -> rescheduleIn(POLL_INTERVAL);
            case Completed completed -> {
                archival.recordArchived(poll.id(), completed.paperlessDocumentId());
                yield remove();
            }
            case Duplicate duplicate -> {
                archival.recordArchived(poll.id(), duplicate.existingPaperlessDocumentId());
                yield remove();
            }
            case Failed failed -> afterFailure(archival.recordFailure(poll.id(), failed.code(), failed.message(),
                    false));
        };
    }

    private CompletionHandler<ArchiveDocumentPayload> afterFailure(FailureDecision decision) {
        return decision.retry() ? rescheduleIn(backoff(decision.attempts())) : remove();
    }

    static Duration backoff(int attempts) {
        long factor = 1L << Math.max(0, Math.min(attempts - 1, 20));
        Duration delay = RETRY_BASE.multipliedBy(factor);
        return delay.compareTo(RETRY_MAX) > 0 ? RETRY_MAX : delay;
    }

    private CompletionHandler<ArchiveDocumentPayload> rescheduleIn(Duration delay) {
        Instant next = clock.instant().plus(delay);
        return (complete, operations) -> operations.reschedule(complete, next);
    }

    private static CompletionHandler<ArchiveDocumentPayload> remove() {
        return (complete, operations) -> operations.stop();
    }
}
