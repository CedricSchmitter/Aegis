package dev.aegis.operations.application;

import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.JobHealthWatch;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.ports.JobIncidentStore;
import dev.aegis.operations.application.ports.JobIncidentStore.JobIncident;
import dev.aegis.operations.application.ports.RecurringJobStateSource;
import dev.aegis.operations.application.ports.RecurringJobStateSource.RecurringJobState;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the job health of the status page (ADR-0013) into notices (ADR-0014). A job that is STALE or NOT_SCHEDULED
 * opens an incident, and every active member receives one JOB_STALE notice for it, deduplicated by the incident id.
 * The incident stays open until the job is OK again, so a job that recovers and fails later opens a new incident.
 *
 * <p>Each step is idempotent instead of being part of one transaction: while an incident is open, every check
 * publishes its notices again and the notice deduplication keeps one per member, so a check that stopped half way
 * completes on the next run. Right after the application started, jobs that were due during the downtime have not run
 * yet; the watch opens no incident until {@link JobHealthPolicy#GRACE} has passed since it started.
 */
public class JobHealthWatchService implements JobHealthWatch {
    static final String NOTICE_KIND = "JOB_STALE";
    private static final Set<String> ALERTING = Set.of(JobHealth.STALE, JobHealth.NOT_SCHEDULED);

    private final List<RecurringJob> jobs;
    private final RecurringJobStateSource jobStates;
    private final JobIncidentStore incidents;
    private final MemberDirectory members;
    private final PublishNotice notices;
    private final Clock clock;
    private final Instant watchingSince;

    public JobHealthWatchService(List<RecurringJob> jobs, RecurringJobStateSource jobStates,
            JobIncidentStore incidents, MemberDirectory members, PublishNotice notices, Clock clock) {
        this.jobs = List.copyOf(jobs);
        this.jobStates = jobStates;
        this.incidents = incidents;
        this.members = members;
        this.notices = notices;
        this.clock = clock;
        this.watchingSince = clock.instant();
    }

    /**
     * @throws RuntimeException when the scheduler state cannot be read at all; then no job can be judged and the
     *     watch's own run fails, which the status page shows
     */
    @Override
    public Report check() {
        Map<String, RecurringJobState> states = jobStates.states(jobs.stream().map(RecurringJob::name).toList());
        Instant now = clock.instant();
        boolean settled = !now.isBefore(watchingSince.plus(JobHealthPolicy.GRACE));
        Recipients recipients = new Recipients();
        int opened = 0;
        int resolved = 0;
        List<String> failed = new ArrayList<>();
        for (RecurringJob job : jobs) {
            try {
                JobHealth health = JobHealthPolicy.evaluate(job, states.get(job.name()), now);
                Optional<JobIncident> open = incidents.findOpen(job.name());
                if (JobHealth.OK.equals(health.state())) {
                    if (open.isPresent()) {
                        incidents.resolve(open.get().id(), now);
                        resolved++;
                    }
                    continue;
                }
                if (open.isEmpty() && (!settled || !ALERTING.contains(health.state()))) {
                    continue;
                }
                JobIncident incident = open.orElseGet(
                        () -> incidents.openIfAbsent(job.name(), health.state(), health.lastSuccessAt(), now));
                if (open.isEmpty()) {
                    opened++;
                }
                publish(incident, recipients.get());
            } catch (RuntimeException failure) {
                failed.add(job.name());
            }
        }
        return new Report(opened, resolved, failed);
    }

    private void publish(JobIncident incident, List<MemberSummary> recipients) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("job", incident.jobName());
        params.put("state", incident.state());
        if (incident.lastSuccessAt() != null) {
            params.put("lastSuccessAt", incident.lastSuccessAt().toString());
        }
        for (MemberSummary member : recipients) {
            notices.publish(new NoticeDraft(member.id(), NOTICE_KIND, "job-health:" + incident.id(), params,
                    "STATUS", incident.jobName()));
        }
    }

    /** The active members, read once per check and only when a notice is due. */
    private final class Recipients {
        private List<MemberSummary> active;

        List<MemberSummary> get() {
            if (active == null) {
                active = members.listMembers(WATCH).stream().filter(MemberSummary::active).toList();
            }
            return active;
        }
    }
}
