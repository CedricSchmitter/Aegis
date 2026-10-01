package dev.aegis.documents.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.documents.application.BulkAnalysisDriver;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable driver of a bulk analysis (issue #115). One job instance per run, created in the transaction that starts
 * the run; each execution performs one step and reschedules itself until the run ended. Nothing schedules this job
 * on startup: only a member's start does, and a restart continues a run that was already started.
 */
@Configuration
public class BulkAnalysisTask {
    public static final TaskDescriptor<BulkAnalysisPayload> DESCRIPTOR = TaskDescriptor.of("document-bulk-analysis",
            BulkAnalysisPayload.class);

    private final BulkAnalysisDriver driver;

    public BulkAnalysisTask(BulkAnalysisDriver driver) {
        this.driver = driver;
    }

    @Bean
    CustomTask<BulkAnalysisPayload> bulkAnalysisJob() {
        return Tasks.custom(DESCRIPTOR)
                // Unexpected exceptions (database outage, a concurrent edit of the same analysis) roll the step back;
                // the step is repeated later and its effects happen once.
                .onFailureReschedule(FixedDelay.ofMinutes(1))
                .onDeadExecutionRevive()
                .execute(this::run);
    }

    CompletionHandler<BulkAnalysisPayload> run(TaskInstance<BulkAnalysisPayload> instance, ExecutionContext context) {
        BulkAnalysisDriver.Step step = driver.advance(UUID.fromString(instance.getData().bulkId()));
        return switch (step) {
            case BulkAnalysisDriver.Continue next -> (complete, operations) -> operations.reschedule(complete,
                    next.at());
            case BulkAnalysisDriver.Done done -> (complete, operations) -> operations.stop();
        };
    }
}
