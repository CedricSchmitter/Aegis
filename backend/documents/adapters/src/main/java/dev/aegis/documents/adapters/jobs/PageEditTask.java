package dev.aegis.documents.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.documents.application.ArchivalSettings;
import dev.aegis.documents.application.PageEditRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable execution of a page editor operation (ADR-0024). One job instance per operation, created in the
 * transaction that stores it. An attempt that could not read the archive is rescheduled with the archive jobs'
 * backoff until the operation's attempts are used up.
 */
@Configuration
public class PageEditTask {
    public static final TaskDescriptor<PageEditPayload> DESCRIPTOR = TaskDescriptor.of("document-page-edit",
            PageEditPayload.class);
    private static final Logger log = LoggerFactory.getLogger(PageEditTask.class);

    private final PageEditRunner runner;
    private final ArchivalSettings settings;
    private final Clock clock;

    public PageEditTask(PageEditRunner runner, ArchivalSettings settings, Clock clock) {
        this.runner = runner;
        this.settings = settings;
        this.clock = clock;
    }

    @Bean
    CustomTask<PageEditPayload> pageEditJob() {
        return Tasks.custom(DESCRIPTOR)
                // Unexpected exceptions (database outage, a bug) are retried by the scheduler; every output is
                // created once because a repeated attempt finds the outputs registered before.
                .onFailureReschedule(FixedDelay.ofMinutes(5))
                .onDeadExecutionRevive()
                .execute(this::run);
    }

    CompletionHandler<PageEditPayload> run(TaskInstance<PageEditPayload> instance, ExecutionContext context) {
        UUID id = UUID.fromString(instance.getData().pageEditId());
        PageEditRunner.Outcome outcome = runner.run(id);
        return switch (outcome) {
            case PageEditRunner.Done done -> {
                log.info("Page edit {} ended: {}", id, done.reason());
                yield (complete, operations) -> operations.stop();
            }
            case PageEditRunner.Retry retry -> {
                Instant next = clock.instant().plus(ArchiveDocumentTask.backoff(retry.attempts(),
                        settings.retryBase()));
                log.warn("Page edit {} retries at {}: {}", id, next, retry.reason());
                yield (complete, operations) -> operations.reschedule(complete, next);
            }
        };
    }
}
