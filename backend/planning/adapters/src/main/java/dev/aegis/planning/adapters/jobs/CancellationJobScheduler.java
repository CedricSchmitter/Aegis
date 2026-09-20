package dev.aegis.planning.adapters.jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import dev.aegis.contracts.application.api.ContractReconciliationScheduler;
import dev.aegis.planning.application.ports.CancellationReminderScheduler;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Transaction-aware durable scheduling for contract reconciliation and occurrence reminders. */
@Component
public class CancellationJobScheduler
        implements ContractReconciliationScheduler, CancellationReminderScheduler {
    private final ObjectProvider<SchedulerClient> client;
    private final Clock clock;

    public CancellationJobScheduler(ObjectProvider<SchedulerClient> client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public void schedule(ContractId contractId, MemberId requestedBy) {
        SchedulerClient scheduler = client.getObject();
        String changeId = "change:" + contractId + ":" + UUID.randomUUID();
        scheduler.schedule(ContractCancellationTasks.RECONCILE.instance(changeId)
                .data(ContractReconciliationPayload.oneShot(contractId.toString(), requestedBy.toString()))
                .scheduledTo(clock.instant()));
        Instant nextDailyRun = LocalDate.now(clock).plusDays(1).atTime(0, 5).atZone(clock.getZone()).toInstant();
        scheduler.scheduleIfNotExists(ContractCancellationTasks.RECONCILE.instance("daily:" + contractId)
                .data(ContractReconciliationPayload.recurring(contractId.toString(), requestedBy.toString()))
                .scheduledTo(nextDailyRun));
    }

    @Override
    public void schedule(TaskId taskId, MemberId requestedBy, Instant at) {
        client.getObject().scheduleIfNotExists(ContractCancellationTasks.REMIND.instance(taskId.toString())
                .data(CancellationReminderPayload.of(taskId.toString(), requestedBy.toString())).scheduledTo(at));
    }
}
