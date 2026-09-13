package dev.aegis.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntegrationStatusServiceTest {
    @Test
    void failingProbeIsReportedUnavailableWithoutHidingOthers() {
        IntegrationStatusSource healthy = source(2, actor -> new IntegrationStatus("database",
                IntegrationStatus.CONNECTED, null, null, Instant.EPOCH));
        IntegrationStatusSource broken = source(1, actor -> {
            throw new IllegalStateException("boom");
        });
        var service = new IntegrationStatusService(List.of(healthy, broken), actor -> Map.of("ARCHIVED", 3L),
                actor -> {
                }, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        var status = service.status(new Actor(MemberId.random()));

        assertThat(status.integrations()).extracting("state").containsExactly(IntegrationStatus.UNAVAILABLE,
                IntegrationStatus.CONNECTED);
        assertThat(status.integrations().get(0).detail()).contains("boom");
        assertThat(status.documentProcessing()).containsEntry("ARCHIVED", 3L);
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
