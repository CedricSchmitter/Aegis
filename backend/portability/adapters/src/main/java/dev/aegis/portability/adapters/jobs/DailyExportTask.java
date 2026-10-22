package dev.aegis.portability.adapters.jobs;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Daily;
import dev.aegis.portability.application.api.RefreshExport;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The daily export (ADR-0029), by default at 02:30 household time, an hour before the nightly external backup copies
 * the export directory to the backup disk. It runs as the exporter household process. db-scheduler keeps the
 * schedule in PostgreSQL and runs one execution at a time; a failed run is repeated at the next scheduled time. A
 * repeated run is harmless: it either finds the data unchanged or publishes one more complete generation.
 */
@Configuration
public class DailyExportTask {
    public static final String NAME = "refresh-export";
    /** The time between two runs, reported on the status page (ADR-0013). */
    public static final Duration INTERVAL = Duration.ofDays(1);
    private static final Logger log = LoggerFactory.getLogger(DailyExportTask.class);

    private final RefreshExport refresh;
    private final LocalTime at;
    private final ZoneId zone;

    public DailyExportTask(RefreshExport refresh, @Value("${aegis.exports.daily-at:02:30}") String at,
            @Value("${aegis.household.zone:Europe/Berlin}") String zone) {
        this.refresh = refresh;
        this.at = LocalTime.parse(at);
        this.zone = ZoneId.of(zone);
    }

    @Bean
    RecurringTask<Void> refreshExportJob() {
        return Tasks.recurring(NAME, new Daily(zone, at)).execute((instance, context) -> {
            long started = System.nanoTime();
            RefreshExport.Result result = refresh.refresh(RefreshExport.EXPORTER);
            long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            log.info("Daily export: outcome={} generation={} pruned={} durationMs={}", result.outcome(),
                    result.current().generation(), result.pruned().size(), millis);
        });
    }
}
