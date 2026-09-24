package dev.aegis.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.JobHealthWatch;
import dev.aegis.operations.application.api.JobHealthWatch.Report;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.ports.JobIncidentStore;
import dev.aegis.operations.application.ports.RecurringJobStateSource.RecurringJobState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobHealthWatchServiceTest {
    private static final Instant START = Instant.parse("2026-09-24T08:00:00Z");
    private static final String REVIEW = "reconcile-review-inbox";
    private static final String TEXT = "check-archive-text";
    private static final List<RecurringJob> JOBS = List.of(new RecurringJob(REVIEW, Duration.ofDays(1)),
            new RecurringJob(TEXT, Duration.ofMinutes(1)));

    private final MutableClock clock = new MutableClock(START);
    private final Map<String, RecurringJobState> states = new HashMap<>();
    private final InMemoryIncidents incidents = new InMemoryIncidents();
    private final MemberSummary anna = member("anna", true);
    private final MemberSummary ben = member("ben", true);
    private final MemberSummary former = member("former", false);
    private final List<Actor> memberReaders = new ArrayList<>();
    private final MemberDirectory directory = new MemberDirectory() {
        @Override
        public List<MemberSummary> listMembers(Actor actor) {
            memberReaders.add(actor);
            return List.of(anna, ben, former);
        }

        @Override
        public Optional<MemberSummary> findMember(MemberId id) {
            return Optional.empty();
        }
    };
    private final RecordingNotices notices = new RecordingNotices();
    private final JobHealthWatchService watch = new JobHealthWatchService(JOBS, names -> Map.copyOf(states),
            incidents, directory, notices, clock);

    @Test
    void aJobThatBecomesStaleNotifiesEveryActiveMemberOnceWithStableParametersOnly() {
        Instant lastSuccess = START.minus(Duration.ofMinutes(5));
        clock.set(START.plus(Duration.ofHours(1)));
        states.put(REVIEW, healthy(lastSuccess));
        states.put(TEXT, healthy(clock.instant()));
        assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));
        assertThat(notices.published).isEmpty();

        // The daily run failed twice: no success for more than two days and ten minutes.
        clock.set(lastSuccess.plus(Duration.ofDays(2)).plus(Duration.ofMinutes(11)));
        states.put(REVIEW, new RecurringJobState(lastSuccess, clock.instant().minus(Duration.ofHours(1)), 2,
                clock.instant().plus(Duration.ofHours(23))));
        states.put(TEXT, healthy(clock.instant()));

        assertThat(watch.check()).isEqualTo(new Report(1, 0, List.of()));

        assertThat(notices.published).extracting(NoticeDraft::recipient).containsExactlyInAnyOrder(anna.id(),
                ben.id());
        UUID incident = incidents.findOpen(REVIEW).orElseThrow().id();
        assertThat(notices.published).allSatisfy(draft -> {
            assertThat(draft.kind()).isEqualTo("JOB_STALE");
            assertThat(draft.dedupKey()).isEqualTo("job-health:" + incident);
            assertThat(draft.params()).containsExactly(Map.entry("job", REVIEW), Map.entry("state", JobHealth.STALE),
                    Map.entry("lastSuccessAt", lastSuccess.toString()));
            assertThat(draft.linkType()).isEqualTo("STATUS");
            assertThat(draft.linkId()).isEqualTo(REVIEW);
        });
        assertThat(memberReaders).containsOnly(JobHealthWatch.WATCH);
    }

    @Test
    void repeatedChecksDuringOneIncidentKeepOneNoticePerMember() {
        settle();
        states.put(REVIEW, healthy(clock.instant()));
        states.put(TEXT, healthy(clock.instant().minus(Duration.ofMinutes(30))));

        assertThat(watch.check().opened()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(5));
        assertThat(watch.check().opened()).isZero();
        clock.advance(Duration.ofMinutes(5));
        watch.check();

        assertThat(notices.delivered()).hasSize(2);
        assertThat(incidents.all).hasSize(1);
    }

    @Test
    void aCheckThatStoppedHalfWayCompletesItsNoticesOnTheNextRun() {
        settle();
        states.put(REVIEW, healthy(clock.instant()));
        states.put(TEXT, healthy(clock.instant().minus(Duration.ofMinutes(30))));
        notices.failFor = ben.id();

        assertThat(watch.check().failed()).containsExactly(TEXT);
        assertThat(notices.delivered()).extracting(NoticeDraft::recipient).containsExactly(anna.id());

        notices.failFor = null;
        clock.advance(Duration.ofMinutes(5));
        assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));
        assertThat(notices.delivered()).extracting(NoticeDraft::recipient).containsExactlyInAnyOrder(anna.id(),
                ben.id());
    }

    @Test
    void aRecoveredJobClosesItsIncidentAndALaterOutageIsANewIncident() {
        settle();
        states.put(REVIEW, healthy(clock.instant()));
        states.put(TEXT, healthy(clock.instant().minus(Duration.ofMinutes(30))));
        watch.check();
        UUID first = incidents.findOpen(TEXT).orElseThrow().id();

        clock.advance(Duration.ofMinutes(5));
        states.put(TEXT, healthy(clock.instant()));
        assertThat(watch.check()).isEqualTo(new Report(0, 1, List.of()));
        assertThat(incidents.findOpen(TEXT)).isEmpty();

        // Failing again is not yet stale; only the stale job opens the next incident.
        clock.advance(Duration.ofMinutes(2));
        states.put(TEXT, new RecurringJobState(clock.instant().minus(Duration.ofMinutes(2)), clock.instant(), 1,
                clock.instant().plus(Duration.ofMinutes(1))));
        assertThat(watch.check().opened()).isZero();
        clock.advance(Duration.ofMinutes(20));
        assertThat(watch.check().opened()).isEqualTo(1);

        UUID second = incidents.findOpen(TEXT).orElseThrow().id();
        assertThat(second).isNotEqualTo(first);
        assertThat(notices.delivered()).hasSize(4);
        assertThat(notices.delivered()).extracting(NoticeDraft::dedupKey).containsOnly("job-health:" + first,
                "job-health:" + second);
    }

    @Test
    void aJobWithoutScheduleIsReportedWithoutALastSuccess() {
        settle();
        states.put(TEXT, healthy(clock.instant()));

        assertThat(watch.check().opened()).isEqualTo(1);

        assertThat(notices.delivered()).hasSize(2).allSatisfy(draft -> assertThat(draft.params())
                .containsExactly(Map.entry("job", REVIEW), Map.entry("state", JobHealth.NOT_SCHEDULED)));
    }

    @Test
    void rightAfterTheStartDueJobsGetTheirRunBeforeAnIncidentOpens() {
        // After a downtime every job looks stale until its overdue run has happened.
        Instant beforeDowntime = START.minus(Duration.ofDays(3));
        states.put(REVIEW, healthy(beforeDowntime));
        states.put(TEXT, healthy(beforeDowntime));

        assertThat(watch.check().opened()).isZero();
        clock.advance(Duration.ofMinutes(5));
        states.put(TEXT, healthy(clock.instant()));
        assertThat(watch.check().opened()).isZero();

        clock.advance(Duration.ofMinutes(5));
        states.put(TEXT, healthy(clock.instant()));
        assertThat(watch.check().opened()).isEqualTo(1);
        assertThat(incidents.all).extracting(JobIncidentStore.JobIncident::jobName).containsExactly(REVIEW);
    }

    @Test
    void aJobThatCannotBeCheckedDoesNotHideTheOthers() {
        settle();
        states.put(REVIEW, healthy(clock.instant().minus(Duration.ofDays(3))));
        states.put(TEXT, healthy(clock.instant().minus(Duration.ofMinutes(30))));
        incidents.failFor = REVIEW;

        assertThat(watch.check()).isEqualTo(new Report(1, 0, List.of(REVIEW)));

        assertThat(incidents.all).extracting(JobIncidentStore.JobIncident::jobName).containsExactly(TEXT);
        assertThat(notices.delivered()).hasSize(2);
    }

    @Test
    void anUnreadableSchedulerFailsTheWholeCheckWithoutChangingIncidents() {
        JobHealthWatchService unreadable = new JobHealthWatchService(JOBS, names -> {
            throw new IllegalStateException("database down");
        }, incidents, directory, notices, clock);
        clock.advance(Duration.ofHours(1));

        assertThatThrownBy(unreadable::check).isInstanceOf(IllegalStateException.class);
        assertThat(incidents.all).isEmpty();
        assertThat(notices.published).isEmpty();
    }

    private void settle() {
        clock.advance(Duration.ofHours(1));
    }

    private static RecurringJobState healthy(Instant lastSuccess) {
        return new RecurringJobState(lastSuccess, null, 0, lastSuccess.plus(Duration.ofMinutes(1)));
    }

    private static MemberSummary member(String username, boolean active) {
        return new MemberSummary(MemberId.random(), username, username, ProductLocale.DE, active);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Deduplicates like the notice inbox: one notice per recipient and key. */
    private static final class RecordingNotices implements PublishNotice {
        final List<NoticeDraft> published = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        MemberId failFor;

        @Override
        public PublishOutcome publish(NoticeDraft draft) {
            if (draft.recipient().equals(failFor)) {
                throw new IllegalStateException("inbox unavailable");
            }
            if (!keys.add(draft.recipient() + "|" + draft.dedupKey())) {
                return PublishOutcome.DEDUPLICATED;
            }
            published.add(draft);
            return PublishOutcome.PUBLISHED;
        }

        List<NoticeDraft> delivered() {
            return published;
        }
    }

    private static final class InMemoryIncidents implements JobIncidentStore {
        final List<JobIncident> all = new ArrayList<>();
        final Map<String, JobIncident> open = new LinkedHashMap<>();
        String failFor;

        @Override
        public Optional<JobIncident> findOpen(String jobName) {
            if (jobName.equals(failFor)) {
                throw new IllegalStateException("store unavailable");
            }
            return Optional.ofNullable(open.get(jobName));
        }

        @Override
        public JobIncident openIfAbsent(String jobName, String state, Instant lastSuccessAt, Instant now) {
            return open.computeIfAbsent(jobName, name -> {
                JobIncident incident = new JobIncident(UUID.randomUUID(), name, state, lastSuccessAt, now);
                all.add(incident);
                return incident;
            });
        }

        @Override
        public void resolve(UUID incidentId, Instant now) {
            open.values().removeIf(incident -> incident.id().equals(incidentId));
        }
    }
}
