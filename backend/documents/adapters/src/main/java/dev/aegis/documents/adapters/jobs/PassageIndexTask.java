package dev.aegis.documents.adapters.jobs;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.aegis.documents.application.PassageIndexWorker;
import dev.aegis.shared.Actor;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/** Persistent scheduler plus documents-owned outbox: five documents per minute, one sequential worker. */
@Configuration
public class PassageIndexTask {
    public static final String NAME="index-document-passages";
    public static final Duration INTERVAL=Duration.ofMinutes(1);
    private static final Logger log=LoggerFactory.getLogger(PassageIndexTask.class);
    @Bean
    RecurringTask<Void> indexDocumentPassages(PassageIndexWorker worker,
            @Value("${aegis.documents.passages.interval:1m}") Duration interval) {
        return Tasks.recurring(NAME,FixedDelay.of(interval)).execute((instance,context) -> {
            var report=worker.sweep(Actor.householdProcess(PassageIndexWorker.PROCESS));
            if (report.prepared()>0) {
                log.info("Passage indexing: prepared={} published={} failed={}",report.prepared(),report.published(),report.failed());
            }
        });
    }
}
