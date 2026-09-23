package dev.aegis.documents.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.documents.application.ArchiveTextService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recurring sweep that records whether the archive recognized text (ADR-0012) for documents the archival job could
 * not check: those archived before the check existed and those whose check failed. One small batch per minute keeps
 * the load on the archive low; the stored checks are the progress, so a restart simply continues.
 */
@Configuration
public class ArchiveTextCheckTask {
    public static final String NAME = "check-archive-text";
    public static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(ArchiveTextCheckTask.class);

    private final ArchiveTextService textChecks;

    public ArchiveTextCheckTask(ArchiveTextService textChecks) {
        this.textChecks = textChecks;
    }

    @Bean
    RecurringTask<Void> checkArchiveTextJob() {
        return Tasks.recurring(NAME, FixedDelay.of(INTERVAL)).execute((instance, context) -> {
            ArchiveTextService.SweepReport report = textChecks.sweep();
            if (report.measured() > 0 || report.failed() > 0) {
                log.info("Archive text checks: measured={} failed={}", report.measured(), report.failed());
            }
        });
    }
}
