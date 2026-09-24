package dev.aegis.operations.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.operations.application.api.JobHealthWatch;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recurring watch over the other recurring jobs (issue #94, ADR-0014), by default every five minutes. It is a job of
 * its own, so a watched job that fails or is no longer scheduled cannot stop it. A run in which a job could not be
 * checked fails after checking all others, so the status page shows the watch itself as failing.
 */
@Configuration
public class JobHealthWatchTask {
    public static final String NAME = "watch-job-health";
    private static final Logger log = LoggerFactory.getLogger(JobHealthWatchTask.class);

    private final JobHealthWatch watch;
    private final Duration interval;

    public JobHealthWatchTask(JobHealthWatch watch,
            @Value("${aegis.operations.job-health-watch-interval:5m}") Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("the job health watch interval must be positive");
        }
        this.watch = watch;
        this.interval = interval;
    }

    /** The configured time between two runs, reported on the status page (ADR-0013). */
    public Duration interval() {
        return interval;
    }

    @Bean
    RecurringTask<Void> watchJobHealthJob() {
        return Tasks.recurring(NAME, FixedDelay.of(interval)).execute((instance, context) -> {
            JobHealthWatch.Report report = watch.check();
            if (report.opened() > 0 || report.resolved() > 0) {
                log.info("Job health watch: opened={} resolved={}", report.opened(), report.resolved());
            }
            if (!report.failed().isEmpty()) {
                throw new IllegalStateException("job health could not be checked for " + report.failed());
            }
        });
    }
}
