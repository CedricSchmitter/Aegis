package dev.aegis.documents.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.documents.application.ReconciliationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Recurring reconciliation of filesystem, database and scheduler state. */
@Configuration
public class ReconciliationTask {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationTask.class);

    private final ReconciliationService reconciliation;

    public ReconciliationTask(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Bean
    RecurringTask<Void> reconcileDocumentsJob() {
        return Tasks.recurring("reconcile-documents", FixedDelay.ofMinutes(5)).execute((instance, context) -> {
            try {
                var report = reconciliation.reconcile();
                if (report.rescheduled() > 0 || report.quarantined() > 0 || report.stagingRemoved() > 0) {
                    log.info("Reconciliation: rescheduled={} quarantined={} stagingRemoved={} notes={}",
                            report.rescheduled(), report.quarantined(), report.stagingRemoved(), report.notes());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
