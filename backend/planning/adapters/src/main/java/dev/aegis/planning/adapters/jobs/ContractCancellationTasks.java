package dev.aegis.planning.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.planning.application.api.CancellationDeadlineReconciliation;
import dev.aegis.planning.application.api.CancellationReminderPublication;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable AI-free jobs. Every execution rechecks current versioned state before writing or notifying. */
@Configuration
public class ContractCancellationTasks {
    public static final TaskDescriptor<ContractReconciliationPayload> RECONCILE = TaskDescriptor.of(
            "reconcile-contract-cancellation", ContractReconciliationPayload.class);
    public static final TaskDescriptor<CancellationReminderPayload> REMIND = TaskDescriptor.of(
            "publish-contract-cancellation-reminder", CancellationReminderPayload.class);

    private final CancellationDeadlineReconciliation reconciliation;
    private final CancellationReminderPublication publication;
    private final Clock clock;

    public ContractCancellationTasks(CancellationDeadlineReconciliation reconciliation,
            CancellationReminderPublication publication, Clock clock) {
        this.reconciliation = reconciliation;
        this.publication = publication;
        this.clock = clock;
    }

    @Bean
    CustomTask<ContractReconciliationPayload> reconcileContractCancellationJob() {
        return Tasks.custom(RECONCILE).onFailureReschedule(FixedDelay.ofMinutes(2)).onDeadExecutionRevive()
                .execute(this::reconcile);
    }

    @Bean
    CustomTask<CancellationReminderPayload> publishContractCancellationReminderJob() {
        return Tasks.custom(REMIND).onFailureReschedule(FixedDelay.ofMinutes(2)).onDeadExecutionRevive()
                .execute(this::remind);
    }

    private CompletionHandler<ContractReconciliationPayload> reconcile(
            TaskInstance<ContractReconciliationPayload> instance,
            com.github.kagkarlsson.scheduler.task.ExecutionContext context) {
        var payload = instance.getData();
        reconciliation.reconcile(actor(payload.requestedBy()), ContractId.of(payload.contractId()));
        if (!payload.recurring()) {
            return (complete, operations) -> operations.stop();
        }
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        var next = tomorrow.atTime(0, 5).atZone(clock.getZone()).toInstant();
        return (complete, operations) -> operations.reschedule(complete, next);
    }

    private CompletionHandler<CancellationReminderPayload> remind(TaskInstance<CancellationReminderPayload> instance,
            com.github.kagkarlsson.scheduler.task.ExecutionContext context) {
        var payload = instance.getData();
        publication.publish(actor(payload.requestedBy()), TaskId.of(payload.taskId()));
        return (complete, operations) -> operations.stop();
    }

    private static Actor actor(String memberId) {
        return new Actor(MemberId.of(memberId));
    }
}
