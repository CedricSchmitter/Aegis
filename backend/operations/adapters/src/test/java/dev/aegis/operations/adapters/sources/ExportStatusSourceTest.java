package dev.aegis.operations.adapters.sources;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.portability.application.api.ExportQueries;
import dev.aegis.portability.application.api.ExportStatus;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExportStatusSourceTest {
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final Instant GENERATED = Instant.parse("2026-10-19T00:30:00Z");
    private static final ExportSummary LATEST = new ExportSummary("g", GENERATED, 1, 0, 2, 0, List.of(), "/x",
            ExportSummary.SCHEDULED, "f");
    private final Actor member = new Actor(MemberId.random());

    @Test
    void currentWhenTheLastRunConfirmedTheLatestGeneration() {
        Instant checked = NOW.minus(Duration.ofHours(9));

        HealthCheck check = check(new ExportStatus(LATEST, checked, null));

        assertThat(check.state()).isEqualTo(HealthCheck.OK);
        assertThat(check.reason()).isEqualTo("export.ok");
        assertThat(check.lastSuccessAt()).isEqualTo(checked);
        assertThat(check.params()).containsEntry("generatedAt", GENERATED.toString());
    }

    @Test
    void aFailureAfterTheLastSuccessIsAProblemThatStillNamesTheLastGoodGeneration() {
        Instant checked = NOW.minus(Duration.ofDays(1));

        HealthCheck check = check(new ExportStatus(LATEST, checked, NOW.minus(Duration.ofHours(9))));

        assertThat(check.state()).isEqualTo(HealthCheck.PROBLEM);
        assertThat(check.reason()).isEqualTo("export.failed");
        assertThat(check.lastSuccessAt()).isEqualTo(checked);
        assertThat(check.params()).containsEntry("generatedAt", GENERATED.toString());
    }

    @Test
    void anOlderFailureIsForgottenOnceARunSucceeded() {
        HealthCheck check = check(new ExportStatus(LATEST, NOW.minus(Duration.ofHours(9)),
                NOW.minus(Duration.ofDays(1))));

        assertThat(check.reason()).isEqualTo("export.ok");
    }

    @Test
    void staleWhenNoRunFinishedForTwoDays() {
        HealthCheck check = check(new ExportStatus(LATEST, NOW.minus(Duration.ofDays(3)), null));

        assertThat(check.state()).isEqualTo(HealthCheck.PROBLEM);
        assertThat(check.reason()).isEqualTo("export.stale");
    }

    @Test
    void uncheckedBeforeTheFirstRun() {
        HealthCheck check = check(new ExportStatus(null, null, null));

        assertThat(check.state()).isEqualTo(HealthCheck.UNCHECKED);
        assertThat(check.reason()).isEqualTo("export.pending");
        assertThat(check.params()).isEmpty();
    }

    private HealthCheck check(ExportStatus status) {
        ExportQueries queries = new ExportQueries() {
            @Override
            public List<ExportSummary> list(Actor actor) {
                return List.of();
            }

            @Override
            public Optional<ExportSummary> latest(Actor actor) {
                return Optional.ofNullable(status.latest());
            }

            @Override
            public ExportStatus status(Actor actor) {
                return status;
            }
        };
        return new ExportStatusSource(queries, Clock.fixed(NOW, ZoneOffset.UTC)).check(member).orElseThrow();
    }
}
