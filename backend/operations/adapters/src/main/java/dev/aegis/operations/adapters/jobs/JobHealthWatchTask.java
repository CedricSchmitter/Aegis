package dev.aegis.operations.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.operations.application.api.JobHealthWatch;
import dev.aegis.operations.application.api.StorageHealthWatch;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recurring watch over the other recurring jobs (ADR-0014), by default every five minutes. It is a job of
 * its own, so a watched job that fails or is no longer scheduled cannot stop it. A run in which a job could not be
 * checked fails after checking all others, so the status page shows the watch itself as failing. The same run also
 * watches the external backup and the free disk space (ADR-0015); the job keeps its name so that its
 * persisted schedule stays valid.
 */
@Configuration
public class JobHealthWatchTask {
    public static final String NAME = "watch-job-health";
    private static final Logger log = LoggerFactory.getLogger(JobHealthWatchTask.class);

    private final JobHealthWatch watch;
    private final StorageHealthWatch storageWatch;
    private final Duration interval;

    public JobHealthWatchTask(JobHealthWatch watch, StorageHealthWatch storageWatch,
            @Value("${aegis.operations.job-health-watch-interval:5m}") Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("the job health watch interval must be positive");
        }
        this.watch = watch;
        this.storageWatch = storageWatch;
        this.interval = interval;
    }

    /** The configured time between two runs, reported on the status page (ADR-0013). */
    public Duration interval() {
        return interval;
    }

    @Bean
    RecurringTask<Void> watchJobHealthJob() {
        return Tasks.recurring(NAME, FixedDelay.of(interval)).execute((instance, context) -> {
            List<String> failed = new ArrayList<>();
            RuntimeException schedulerUnreadable = null;
            try {
                JobHealthWatch.Report jobs = watch.check();
                if (jobs.opened() > 0 || jobs.resolved() > 0) {
                    log.info("Job health watch: opened={} resolved={}", jobs.opened(), jobs.resolved());
                }
                failed.addAll(jobs.failed());
            } catch (RuntimeException unreadable) {
                // The backup and disks are still watched when the scheduler state cannot be read.
                schedulerUnreadable = unreadable;
            }
            JobHealthWatch.Report storage = storageWatch.check();
            if (storage.opened() > 0 || storage.resolved() > 0) {
                log.info("Storage health watch: opened={} resolved={}", storage.opened(), storage.resolved());
            }
            failed.addAll(storage.failed());
            if (schedulerUnreadable != null) {
                throw schedulerUnreadable;
            }
            if (!failed.isEmpty()) {
                throw new IllegalStateException("health could not be checked for " + failed);
            }
        });
    }
}
