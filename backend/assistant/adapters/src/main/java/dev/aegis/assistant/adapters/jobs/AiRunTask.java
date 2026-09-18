package dev.aegis.assistant.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.assistant.application.RunExecutor;
import dev.aegis.assistant.domain.RunId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable execution of AI runs. One job instance per run; each execution performs at most one attempt and the run
 * decides from its persisted state what happens next. A job that dies with its process is revived by the scheduler
 * and the run records the interrupted attempt before trying again.
 */
@Configuration
public class AiRunTask {
    public static final TaskDescriptor<AiRunPayload> DESCRIPTOR = TaskDescriptor.of("ai-run", AiRunPayload.class);

    private final RunExecutor executor;

    public AiRunTask(RunExecutor executor) {
        this.executor = executor;
    }

    @Bean
    CustomTask<AiRunPayload> aiRunJob() {
        return Tasks.custom(DESCRIPTOR)
                // Unexpected exceptions (database outage) are retried by the scheduler; run failures are recorded by
                // the executor and never thrown.
                .onFailureReschedule(FixedDelay.ofMinutes(2))
                .onDeadExecutionRevive()
                .execute(this::run);
    }

    CompletionHandler<AiRunPayload> run(TaskInstance<AiRunPayload> instance, ExecutionContext context) {
        RunExecutor.Outcome outcome = executor.execute(RunId.of(instance.getData().runId()));
        return switch (outcome) {
            case RunExecutor.RunAgainAt again -> (complete, operations) -> operations.reschedule(complete, again.at());
            case RunExecutor.Done done -> (complete, operations) -> operations.stop();
        };
    }
}
