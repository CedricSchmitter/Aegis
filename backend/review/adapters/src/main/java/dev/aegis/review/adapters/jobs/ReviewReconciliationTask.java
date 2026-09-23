package dev.aegis.review.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.review.application.api.ReviewReconciliation;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recurring reconciliation of the review inbox (issue #89, ADR-0009), so a new card and its notices reach both
 * members shortly after their source changed instead of when someone next opens the inbox. It runs as the review
 * reconciler, never as a member. db-scheduler keeps the schedule in PostgreSQL, runs one execution at a time and
 * continues after a restart; a failed run is not retried early but repeated at the next interval, and because the
 * cards are its progress, a repeated or interrupted run creates nothing twice.
 */
@Configuration
public class ReviewReconciliationTask {
    public static final String NAME = "reconcile-review-inbox";
    private static final Logger log = LoggerFactory.getLogger(ReviewReconciliationTask.class);

    private final ReviewReconciliation reconciliation;
    private final Duration interval;

    public ReviewReconciliationTask(ReviewReconciliation reconciliation,
            @Value("${aegis.review.reconciliation-interval:2m}") Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("the review reconciliation interval must be positive");
        }
        this.reconciliation = reconciliation;
        this.interval = interval;
    }

    @Bean
    RecurringTask<Void> reconcileReviewInboxJob() {
        return Tasks.recurring(NAME, FixedDelay.of(interval)).execute((instance, context) -> {
            long started = System.nanoTime();
            ReviewReconciliation.Report report = reconciliation.reconcile(ReviewReconciliation.RECONCILER);
            long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (report.created() > 0 || report.superseded() > 0) {
                log.info("Review reconciliation: candidates={} created={} superseded={} durationMs={}",
                        report.candidates(), report.created(), report.superseded(), millis);
            } else {
                log.debug("Review reconciliation: candidates={} unchanged durationMs={}", report.candidates(), millis);
            }
        });
    }
}
