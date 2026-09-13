package dev.aegis.documents.adapters.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.shared.DocumentId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Schedules archival jobs through db-scheduler. The scheduler client is wired with a transaction-aware data source
 * so the job row commits or rolls back together with the document record.
 */
@Component
public class DbSchedulerProcessingScheduler implements ProcessingScheduler {
    private final SchedulerClient schedulerClient;
    private final Clock clock;

    public DbSchedulerProcessingScheduler(SchedulerClient schedulerClient, Clock clock) {
        this.schedulerClient = schedulerClient;
        this.clock = clock;
    }

    @Override
    public void scheduleArchival(DocumentId id) {
        schedulerClient.scheduleIfNotExists(ArchiveDocumentTask.DESCRIPTOR.instance(id.toString())
                .data(ArchiveDocumentPayload.of(id.toString())).scheduledTo(clock.instant()));
    }

    @Override
    public Optional<Instant> nextAttempt(DocumentId id) {
        return schedulerClient.getScheduledExecution(ArchiveDocumentTask.DESCRIPTOR.instanceId(id.toString()))
                .map(execution -> execution.getExecutionTime());
    }
}
