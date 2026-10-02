package dev.aegis.assistant.adapters.jobs;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import dev.aegis.assistant.application.ports.RunScheduler;
import dev.aegis.assistant.domain.RunId;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Schedules run jobs through db-scheduler. The client uses the transaction-aware data source, so the job row commits
 * or rolls back together with the run. Resolved lazily because the scheduler collects the task beans.
 */
@Component
public class DbSchedulerRunScheduler implements RunScheduler {
    private final ObjectProvider<SchedulerClient> schedulerClient;
    private final ObjectProvider<Scheduler> scheduler;

    public DbSchedulerRunScheduler(ObjectProvider<SchedulerClient> schedulerClient,
            ObjectProvider<Scheduler> scheduler) {
        this.schedulerClient = schedulerClient;
        this.scheduler = scheduler;
    }

    @Override
    public void schedule(RunId id, Instant at) {
        schedulerClient.getObject().scheduleIfNotExists(AiRunTask.DESCRIPTOR.instance(id.toString())
                .data(AiRunPayload.of(id.toString())).scheduledTo(at));
    }

    /**
     * db-scheduler refuses to move a picked execution, and its version check fails if the execution was picked after
     * it was read; both leave the job to its executing run. The poll that db-scheduler triggers right away may run
     * before this transaction commits, so another one is triggered after the commit.
     */
    @Override
    public boolean wake(RunId id, Instant at) {
        SchedulerClient client = schedulerClient.getObject();
        TaskInstanceId job = AiRunTask.DESCRIPTOR.instanceId(id.toString());
        Optional<ScheduledExecution<Object>> current = client.getScheduledExecution(job);
        if (current.isEmpty()) {
            return false;
        }
        if (current.get().isPicked() || !current.get().getExecutionTime().isAfter(at)) {
            return true;
        }
        try {
            client.reschedule(job, at);
        } catch (TaskInstanceNotFoundException gone) {
            return false;
        } catch (TaskInstanceException picked) {
            return true;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    scheduler.ifAvailable(Scheduler::triggerCheckForDueExecutions);
                }
            });
        }
        return true;
    }
}
