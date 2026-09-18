package dev.aegis.assistant.adapters.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import dev.aegis.assistant.application.ports.RunScheduler;
import dev.aegis.assistant.domain.RunId;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Schedules run jobs through db-scheduler. The client uses the transaction-aware data source, so the job row commits
 * or rolls back together with the run. Resolved lazily because the scheduler collects the task beans.
 */
@Component
public class DbSchedulerRunScheduler implements RunScheduler {
    private final ObjectProvider<SchedulerClient> schedulerClient;

    public DbSchedulerRunScheduler(ObjectProvider<SchedulerClient> schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    @Override
    public void schedule(RunId id, Instant at) {
        schedulerClient.getObject().scheduleIfNotExists(AiRunTask.DESCRIPTOR.instance(id.toString())
                .data(AiRunPayload.of(id.toString())).scheduledTo(at));
    }
}
