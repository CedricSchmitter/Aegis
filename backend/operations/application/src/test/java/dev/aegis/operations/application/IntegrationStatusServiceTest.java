package dev.aegis.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.ports.DiskUsage;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.operations.application.ports.RecurringJobStateSource;
import dev.aegis.operations.application.ports.RecurringJobStateSource.RecurringJobState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntegrationStatusServiceTest {
    private static final StorageHealthService STORAGE = new StorageHealthService(Optional::empty,
            () -> new DiskUsage(100_000_000_000L, 60_000_000_000L, Instant.EPOCH), new StorageLimits(0.1, 5_000_000_000L),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void failingProbeIsReportedUnavailableWithoutHidingOthers() {
        IntegrationStatusSource healthy = source(2, actor -> new IntegrationStatus("database",
                IntegrationStatus.CONNECTED, null, null, Instant.EPOCH));
        IntegrationStatusSource broken = source(1, actor -> {
            throw new IllegalStateException("boom");
        });
        var service = new IntegrationStatusService(List.of(healthy, broken), actor -> Map.of("ARCHIVED", 3L),
                List.of(), names -> Map.of(), STORAGE, actor -> {
                }, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        var status = service.status(new Actor(MemberId.random()));

        assertThat(status.integrations()).extracting("state").containsExactly(IntegrationStatus.UNAVAILABLE,
                IntegrationStatus.CONNECTED);
        assertThat(status.integrations().get(0).detail()).contains("boom");
        assertThat(status.documentProcessing()).containsEntry("ARCHIVED", 3L);
        assertThat(status.storage().backup().state()).isEqualTo("NOT_CONFIGURED");
        assertThat(status.storage().disks()).extracting("name", "state").containsExactly(tuple("data", "OK"));
    }

    @Test
    void reportsEveryDeclaredJobInOrderAndUnknownWhenTheSchedulerStateCannotBeRead() {
        Instant now = Instant.parse("2026-09-23T12:00:00Z");
        List<RecurringJob> jobs = List.of(new RecurringJob("reconcile-review-inbox", Duration.ofDays(1)),
                new RecurringJob("check-archive-text", Duration.ofMinutes(1)));
        RecurringJobStateSource stored = names -> Map.of("check-archive-text",
                new RecurringJobState(now.minusSeconds(30), null, 0, now.plusSeconds(30)));
        RecurringJobStateSource unreadable = names -> {
            throw new IllegalStateException("database down");
        };
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Actor member = new Actor(MemberId.random());

        var status = new IntegrationStatusService(List.of(), actor -> Map.of(), jobs, stored, STORAGE, actor -> {
        }, clock).status(member);
        assertThat(status.jobs()).extracting(JobHealth::name, JobHealth::state).containsExactly(
                tuple("reconcile-review-inbox", JobHealth.NOT_SCHEDULED), tuple("check-archive-text", JobHealth.OK));

        var failed = new IntegrationStatusService(List.of(), actor -> Map.of(), jobs, unreadable, STORAGE, actor -> {
        }, clock).status(member);
        assertThat(failed.jobs()).extracting(JobHealth::state).containsOnly(JobHealth.UNKNOWN);
        assertThat(failed.jobs()).extracting(JobHealth::interval)
                .containsExactly(Duration.ofDays(1), Duration.ofMinutes(1));
    }

    @Test
    void theStatusIncludingJobHealthIsForMembersOnly() {
        RecurringJobStateSource states = names -> {
            throw new AssertionError("read before the authorization check");
        };
        var service = new IntegrationStatusService(List.of(), actor -> Map.of(),
                List.of(new RecurringJob("check-archive-text", Duration.ofMinutes(1))), states, STORAGE, actor -> {
                    throw new NotAuthorizedException("members only");
                }, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.status(Actor.householdProcess("review-reconciler")))
                .isInstanceOf(NotAuthorizedException.class);
    }

    private static IntegrationStatusSource source(int order, java.util.function.Function<Actor, IntegrationStatus> fn) {
        return new IntegrationStatusSource() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public IntegrationStatus check(Actor actor) {
                return fn.apply(actor);
            }
        };
    }
}
