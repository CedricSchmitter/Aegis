package dev.aegis.notifications.adapters.push;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.notifications.application.api.PushDispatch;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recurring push delivery (issue #28), by default every minute. db-scheduler keeps the schedule in PostgreSQL and runs
 * one execution at a time; the delivery records are its progress, so a repeated or interrupted run pushes nothing
 * twice except a message whose outcome was not recorded yet.
 */
@Configuration
public class PushDispatchTask {
    public static final String NAME = "dispatch-push-notices";
    private static final Logger log = LoggerFactory.getLogger(PushDispatchTask.class);

    private final PushDispatch dispatch;
    private final Duration interval;

    public PushDispatchTask(PushDispatch dispatch, @Value("${aegis.push.dispatch-interval:1m}") Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("the push dispatch interval must be positive");
        }
        this.dispatch = dispatch;
        this.interval = interval;
    }

    /** The configured time between two runs, reported on the status page (ADR-0013). */
    public Duration interval() {
        return interval;
    }

    @Bean
    RecurringTask<Void> dispatchPushNoticesJob() {
        return Tasks.recurring(NAME, FixedDelay.of(interval)).execute((instance, context) -> {
            PushDispatch.Report report = dispatch.dispatchPending();
            if (report.sent() + report.failed() + report.expired() > 0) {
                log.info("Push dispatch: sent={} failed={} expired={}", report.sent(), report.failed(),
                        report.expired());
            }
        });
    }
}
