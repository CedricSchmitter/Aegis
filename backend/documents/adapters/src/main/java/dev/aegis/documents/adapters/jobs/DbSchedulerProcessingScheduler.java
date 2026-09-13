package dev.aegis.documents.adapters.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.shared.DocumentId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Schedules archival jobs through db-scheduler. The scheduler client is wired with a transaction-aware data source
 * so the job row commits or rolls back together with the document record. The client is resolved lazily because
 * the scheduler itself collects the task beans that depend on this adapter.
 */
@Component
public class DbSchedulerProcessingScheduler implements ProcessingScheduler {
    private final ObjectProvider<SchedulerClient> schedulerClient;
    private final Clock clock;

    public DbSchedulerProcessingScheduler(ObjectProvider<SchedulerClient> schedulerClient, Clock clock) {
        this.schedulerClient = schedulerClient;
        this.clock = clock;
    }

    @Override
    public void scheduleArchival(DocumentId id) {
        schedulerClient.getObject().scheduleIfNotExists(ArchiveDocumentTask.DESCRIPTOR.instance(id.toString())
                .data(ArchiveDocumentPayload.of(id.toString())).scheduledTo(clock.instant()));
    }

    @Override
    public Optional<Instant> nextAttempt(DocumentId id) {
        return schedulerClient.getObject()
                .getScheduledExecution(ArchiveDocumentTask.DESCRIPTOR.instanceId(id.toString()))
                .map(execution -> execution.getExecutionTime());
    }
}
