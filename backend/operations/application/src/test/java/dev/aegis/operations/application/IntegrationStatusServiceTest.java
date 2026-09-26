package dev.aegis.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.operations.application.api.JobHealth;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.ports.DiskUsage;
import dev.aegis.operations.application.ports.HealthCheckSource;
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
    void failingProbeIsAProblemOfItsAreaWithoutItsMessageAndWithoutHidingOthers() {
        HealthCheckSource healthy = source("database", 0,
                actor -> Optional.of(HealthCheck.ok("database", "database.connected", Instant.EPOCH)));
        HealthCheckSource broken = source("archive", 10, actor -> {
            throw new IllegalStateException("boom at http://paperless:8000");
        });
        var service = new IntegrationStatusService(List.of(broken, healthy), actor -> Map.of("ARCHIVED", 3L),
                List.of(), names -> Map.of(), STORAGE, actor -> {
                }, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        var status = service.status(new Actor(MemberId.random()));

        assertThat(status.checks()).extracting(HealthCheck::area, HealthCheck::state, HealthCheck::reason)
                .containsExactly(tuple("database", HealthCheck.OK, "database.connected"),
                        tuple("archive", HealthCheck.PROBLEM, "probe.failed"),
                        tuple("backup", HealthCheck.PROBLEM, "backup.notConfigured"),
                        tuple("storage", HealthCheck.OK, "storage.ok"));
        assertThat(status.checks().get(1).params()).isEmpty();
        assertThat(status.documentProcessing()).containsEntry("ARCHIVED", 3L);
        assertThat(status.storage().backup().state()).isEqualTo("NOT_CONFIGURED");
        assertThat(status.storage().disks()).extracting("name", "state").containsExactly(tuple("data", "OK"));
    }

    @Test
    void integrationsThatAreNotSetUpAreLeftOutAndTheRestKeepTheirOrder() {
        HealthCheckSource ai = source("ai", 60, actor -> Optional.of(new HealthCheck("ai", HealthCheck.UNCHECKED,
                "ai.unchecked", Map.of(), null)));
        HealthCheckSource calendar = source("calendar", 40, actor -> Optional.empty());
        HealthCheckSource database = source("database", 0,
                actor -> Optional.of(HealthCheck.ok("database", "database.connected", Instant.EPOCH)));
        var service = new IntegrationStatusService(List.of(ai, calendar, database), actor -> Map.of(), List.of(),
                names -> Map.of(), STORAGE, actor -> {
                }, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThat(service.status(new Actor(MemberId.random())).checks()).extracting(HealthCheck::area,
                HealthCheck::state).containsExactly(tuple("database", HealthCheck.OK),
                        tuple("backup", HealthCheck.PROBLEM), tuple("storage", HealthCheck.OK),
                        tuple("ai", HealthCheck.UNCHECKED));
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
        assertThat(status.checks().getLast()).isEqualTo(HealthCheck.problem("jobs", "jobs.notRunning",
                Map.of("jobs", "reconcile-review-inbox"), null));

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

    private static HealthCheckSource source(String area, int order,
            java.util.function.Function<Actor, Optional<HealthCheck>> fn) {
        return new HealthCheckSource() {
            @Override
            public String area() {
                return area;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public Optional<HealthCheck> check(Actor actor) {
                return fn.apply(actor);
            }
        };
    }
}
