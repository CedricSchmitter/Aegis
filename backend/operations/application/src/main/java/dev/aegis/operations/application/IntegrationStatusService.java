package dev.aegis.operations.application;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.api.SystemStatus;
import dev.aegis.operations.application.api.SystemStatusQuery;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.operations.application.ports.ProcessingOverviewSource;
import dev.aegis.operations.application.ports.RecurringJobStateSource;
import dev.aegis.operations.application.ports.RecurringJobStateSource.RecurringJobState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Composes the status page from independent probes; a failing probe never hides the others. */
public class IntegrationStatusService implements SystemStatusQuery {
    private final List<IntegrationStatusSource> sources;
    private final ProcessingOverviewSource processing;
    private final List<RecurringJob> jobs;
    private final RecurringJobStateSource jobStates;
    private final HouseholdAccess access;
    private final Clock clock;

    public IntegrationStatusService(List<IntegrationStatusSource> sources, ProcessingOverviewSource processing,
            List<RecurringJob> jobs, RecurringJobStateSource jobStates, HouseholdAccess access, Clock clock) {
        this.sources = sources.stream().sorted(Comparator.comparingInt(IntegrationStatusSource::order)).toList();
        this.processing = processing;
        this.jobs = List.copyOf(jobs);
        this.jobStates = jobStates;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public SystemStatus status(Actor actor) {
        access.requireMember(actor);
        List<IntegrationStatus> statuses = new ArrayList<>();
        for (IntegrationStatusSource source : sources) {
            try {
                statuses.add(source.check(actor));
            } catch (RuntimeException failure) {
                statuses.add(new IntegrationStatus(source.getClass().getSimpleName(), IntegrationStatus.UNAVAILABLE,
                        "probe failed: " + failure.getMessage(), null, clock.instant()));
            }
        }
        return new SystemStatus(statuses, processing.processingCounts(actor), jobHealth(), clock.instant());
    }

    /** Every declared job in its declared order; when the scheduler state cannot be read, each one is UNKNOWN. */
    private List<JobHealth> jobHealth() {
        Map<String, RecurringJobState> states;
        try {
            states = jobStates.states(jobs.stream().map(RecurringJob::name).toList());
        } catch (RuntimeException unreadable) {
            return jobs.stream().map(job -> new JobHealth(job.name(), JobHealth.UNKNOWN, job.interval(), null, null,
                    0, null)).toList();
        }
        Instant now = clock.instant();
        return jobs.stream().map(job -> JobHealthPolicy.evaluate(job, states.get(job.name()), now)).toList();
    }
}
