package dev.aegis.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.operations.application.api.JobHealthWatch;
import dev.aegis.operations.application.api.JobHealthWatch.Report;
import dev.aegis.operations.application.ports.BackupStateSource.BackupState;
import dev.aegis.operations.application.ports.DiskUsage;
import dev.aegis.operations.application.ports.StorageIncidentStore;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorageHealthWatchServiceTest {
    private static final Instant START = Instant.parse("2026-09-24T08:00:00Z");
    private static final long GB = 1_000_000_000L;

    private final MutableClock clock = new MutableClock(START);
    private final MemberSummary anna = member("anna", true);
    private final MemberSummary ben = member("ben", true);
    private final List<Actor> memberReaders = new ArrayList<>();
    private final MemberDirectory directory = new MemberDirectory() {
        @Override
        public List<MemberSummary> listMembers(Actor actor) {
            memberReaders.add(actor);
            return List.of(anna, ben, member("former", false));
        }

        @Override
        public Optional<MemberSummary> findMember(MemberId id) {
            return Optional.empty();
        }
    };
    private final RecordingNotices notices = new RecordingNotices();
    private final InMemoryIncidents incidents = new InMemoryIncidents();
    private Optional<BackupState> backup = Optional.of(succeeded(START.minus(Duration.ofHours(5))));
    private boolean backupUnreadable;
    private DiskUsage data = new DiskUsage(93 * GB, 64 * GB, START);
    private final StorageHealthService storage = new StorageHealthService(() -> {
        if (backupUnreadable) {
            throw new IllegalStateException("not JSON");
        }
        return backup;
    }, () -> data, new StorageLimits(0.10, 5 * GB), clock);
    private final StorageHealthWatchService watch = new StorageHealthWatchService(storage, incidents, directory,
            notices, clock);

    @Test
    void aFailedBackupNotifiesEveryActiveMemberOnceWithItsCodeOnly() {
        assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));
        assertThat(notices.published).isEmpty();

        Instant lastSuccess = START.minus(Duration.ofHours(5));
        backup = Optional.of(new BackupState(false, lastSuccess, START, null, "destination.notMounted", null));

        assertThat(watch.check()).isEqualTo(new Report(1, 0, List.of()));

        UUID incident = incidents.findOpen("backup").orElseThrow().id();
        assertThat(notices.published).extracting(NoticeDraft::recipient).containsExactlyInAnyOrder(anna.id(),
                ben.id());
        assertThat(notices.published).allSatisfy(draft -> {
            assertThat(draft.kind()).isEqualTo("BACKUP_FAILED");
            assertThat(draft.dedupKey()).isEqualTo("storage-health:" + incident);
            assertThat(draft.params()).containsExactly(Map.entry("state", "FAILED"),
                    Map.entry("reason", "destination.notMounted"), Map.entry("lastSuccessAt", lastSuccess.toString()));
            assertThat(draft.linkType()).isEqualTo("STATUS");
            assertThat(draft.linkId()).isEqualTo("backup");
        });
        assertThat(memberReaders).containsOnly(JobHealthWatch.WATCH);
    }

    @Test
    void anOngoingOutageStaysOneIncidentEvenWhenItsReasonChangesOrItBecomesStale() {
        Instant lastSuccess = START.minus(Duration.ofHours(5));
        backup = Optional.of(new BackupState(true, lastSuccess, START, null, "check.failed", null));
        watch.check();

        clock.advance(Duration.ofDays(1));
        backup = Optional.of(new BackupState(false, lastSuccess, clock.instant(), null, "destination.notMounted",
                null));
        assertThat(watch.check().opened()).isZero();
        clock.advance(Duration.ofDays(1));
        backup = Optional.of(new BackupState(true, lastSuccess, lastSuccess, null, null, null));
        assertThat(watch.check().opened()).isZero();

        assertThat(incidents.all).hasSize(1);
        assertThat(notices.published).hasSize(2)
                .allSatisfy(draft -> assertThat(draft.params()).containsEntry("reason", "check.failed"));
    }

    @Test
    void aRunningBackupKeepsTheIncidentAndOnlyASuccessClosesIt() {
        backup = Optional.of(succeeded(START.minus(Duration.ofDays(2))));
        watch.check();
        UUID first = incidents.findOpen("backup").orElseThrow().id();
        assertThat(notices.published).allSatisfy(draft -> assertThat(draft.params())
                .containsExactly(Map.entry("state", "STALE"),
                        Map.entry("lastSuccessAt", START.minus(Duration.ofDays(2)).toString())));

        backup = Optional.of(new BackupState(true, START.minus(Duration.ofDays(2)), START, START, null, null));
        assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));

        clock.advance(Duration.ofMinutes(5));
        backup = Optional.of(succeeded(clock.instant()));
        assertThat(watch.check()).isEqualTo(new Report(0, 1, List.of()));
        assertThat(incidents.findOpen("backup")).isEmpty();

        clock.advance(Duration.ofDays(1));
        backup = Optional.of(new BackupState(true, START, clock.instant(), null, "capture.failed", null));
        assertThat(watch.check().opened()).isEqualTo(1);
        assertThat(incidents.findOpen("backup").orElseThrow().id()).isNotEqualTo(first);
        assertThat(notices.published).hasSize(4);
    }

    @Test
    void aBackupThatIsNotSetUpOrStillRunningNotifiesNobody() {
        backup = Optional.empty();
        assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));
        backup = Optional.of(new BackupState(true, START.minus(Duration.ofDays(3)), START, START, null, null));
        assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));
        assertThat(notices.published).isEmpty();
    }

    @Test
    void anInterruptedRunAndAnUnreadableStateAreIncidentsToo() {
        backup = Optional.of(new BackupState(true, START.minus(Duration.ofHours(5)), START.minus(Duration.ofHours(4)),
                START.minus(Duration.ofHours(4)), null, null));
        watch.check();
        assertThat(notices.published).extracting(draft -> draft.params().get("state")).containsOnly("INTERRUPTED");

        backup = Optional.of(succeeded(START));
        watch.check();
        backupUnreadable = true;
        watch.check();
        assertThat(notices.published.subList(2, 4)).allSatisfy(draft -> assertThat(draft.params())
                .containsExactly(Map.entry("state", "UNKNOWN"), Map.entry("reason", "state.unreadable")));
    }

    @Test
    void aLowDiskNotifiesOnceAndClosesOnlyAfterClearlyRecovering() {
        data = new DiskUsage(93 * GB, 9 * GB, START);
        assertThat(watch.check()).isEqualTo(new Report(1, 0, List.of()));
        UUID first = incidents.findOpen("disk:data").orElseThrow().id();
        assertThat(notices.published).hasSize(2).allSatisfy(draft -> {
            assertThat(draft.kind()).isEqualTo("DISK_SPACE_LOW");
            assertThat(draft.dedupKey()).isEqualTo("storage-health:" + first);
            assertThat(draft.params()).containsExactly(Map.entry("disk", "data"),
                    Map.entry("freeBytes", String.valueOf(9 * GB)), Map.entry("totalBytes", String.valueOf(93 * GB)));
            assertThat(draft.linkId()).isEqualTo("disk:data");
        });

        // Hovering just above the limit neither closes the incident nor notifies again.
        data = new DiskUsage(93 * GB, 9_400_000_000L, START);
        assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));
        data = new DiskUsage(93 * GB, 9 * GB, START);
        watch.check();
        assertThat(notices.published).hasSize(2);

        data = new DiskUsage(93 * GB, 20 * GB, START);
        assertThat(watch.check()).isEqualTo(new Report(0, 1, List.of()));
        assertThat(incidents.findOpen("disk:data")).isEmpty();
    }

    @Test
    void theBackupDiskIsWatchedFromItsLastMeasurementAndAnUnmeasuredDiskIsSkipped() {
        backup = Optional.of(new BackupState(true, START, START, null, null, new DiskUsage(1000 * GB, 60 * GB,
                START)));
        StorageHealthService unmeasurable = new StorageHealthService(() -> backup, () -> {
            throw new IllegalStateException("no such file");
        }, new StorageLimits(0.10, 5 * GB), clock);
        var report = new StorageHealthWatchService(unmeasurable, incidents, directory, notices, clock).check();

        assertThat(report).isEqualTo(new Report(1, 0, List.of()));
        assertThat(incidents.all).extracting(StorageIncidentStore.StorageIncident::subject)
                .containsExactly("disk:backup");
        assertThat(notices.published).allSatisfy(draft -> assertThat(draft.params()).containsEntry("disk", "backup"));
    }

    @Test
    void aSubjectThatCannotBeCheckedDoesNotHideTheOthersAndAHalfWayCheckCompletesLater() {
        backup = Optional.of(new BackupState(true, START, START, null, "check.failed", null));
        data = new DiskUsage(93 * GB, 1 * GB, START);
        incidents.failFor = "backup";
        notices.failFor = ben.id();

        assertThat(watch.check()).isEqualTo(new Report(1, 0, List.of("backup", "disk:data")));
        assertThat(notices.published).extracting(NoticeDraft::recipient).containsExactly(anna.id());

        incidents.failFor = null;
        notices.failFor = null;
        assertThat(watch.check()).isEqualTo(new Report(1, 0, List.of()));
        assertThat(notices.published).hasSize(4);
        assertThat(incidents.all).hasSize(2);
    }

    private static BackupState succeeded(Instant at) {
        return new BackupState(true, at, at, null, null, null);
    }

    private static MemberSummary member(String username, boolean active) {
        return new MemberSummary(MemberId.random(), username, username, ProductLocale.DE, active);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
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
    }

    private static final class InMemoryIncidents implements StorageIncidentStore {
        final List<StorageIncident> all = new ArrayList<>();
        final Map<String, StorageIncident> open = new LinkedHashMap<>();
        String failFor;

        @Override
        public Optional<StorageIncident> findOpen(String subject) {
            if (subject.equals(failFor)) {
                throw new IllegalStateException("store unavailable");
            }
            return Optional.ofNullable(open.get(subject));
        }

        @Override
        public StorageIncident openIfAbsent(StorageIncident proposed) {
            return open.computeIfAbsent(proposed.subject(), subject -> {
                all.add(proposed);
                return proposed;
            });
        }

        @Override
        public void resolve(UUID incidentId, Instant now) {
            open.values().removeIf(incident -> incident.id().equals(incidentId));
        }
    }
}
