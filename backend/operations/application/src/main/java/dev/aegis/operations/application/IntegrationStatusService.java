package dev.aegis.operations.application;

import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.api.StorageHealth;
import dev.aegis.operations.application.api.SystemStatus;
import dev.aegis.operations.application.api.SystemStatusQuery;
import dev.aegis.operations.application.ports.HealthCheckSource;
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
import java.util.Optional;

/**
 * Composes the status page from independent probes; a failing probe never hides the others. The overview lists the
 * probed areas in their order with the backup and storage lines from the storage health (ADR-0015) between them, and
 * a line for the recurring jobs while one of them does not run as planned. Integrations that are not set up are left
 * out.
 */
public class IntegrationStatusService implements SystemStatusQuery {
    private final List<HealthCheckSource> sources;
    private final ProcessingOverviewSource processing;
    private final List<RecurringJob> jobs;
    private final RecurringJobStateSource jobStates;
    private final StorageHealthService storage;
    private final HouseholdAccess access;
    private final Clock clock;

    public IntegrationStatusService(List<HealthCheckSource> sources, ProcessingOverviewSource processing,
            List<RecurringJob> jobs, RecurringJobStateSource jobStates, StorageHealthService storage,
            HouseholdAccess access, Clock clock) {
        this.sources = List.copyOf(sources);
        this.processing = processing;
        this.jobs = List.copyOf(jobs);
        this.jobStates = jobStates;
        this.storage = storage;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public SystemStatus status(Actor actor) {
        access.requireMember(actor);
        StorageHealth storageHealth = storage.current();
        List<JobHealth> jobHealth = jobHealth();
        List<Line> lines = new ArrayList<>();
        for (HealthCheckSource source : sources) {
            probe(source, actor).ifPresent(check -> lines.add(new Line(source.order(), check)));
        }
        lines.add(new Line(StatusOverview.BACKUP_ORDER, StatusOverview.backup(storageHealth.backup())));
        lines.add(new Line(StatusOverview.STORAGE_ORDER, StatusOverview.storage(storageHealth.disks())));
        List<HealthCheck> checks = new ArrayList<>(lines.stream().sorted(Comparator.comparingInt(Line::order))
                .map(Line::check).toList());
        StatusOverview.jobs(jobHealth).ifPresent(checks::add);
        return new SystemStatus(checks, processing.processingCounts(actor), jobHealth, storageHealth,
                clock.instant());
    }

    private static Optional<HealthCheck> probe(HealthCheckSource source, Actor actor) {
        try {
            return source.check(actor);
        } catch (RuntimeException failure) {
            return Optional.of(StatusOverview.probeFailed(source.area()));
        }
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

    private record Line(int order, HealthCheck check) {
    }
}
