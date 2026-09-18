package dev.aegis.operations.adapters.sources;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AiStatusSourceTest {
    static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    static final Instant EARLIER = NOW.minusSeconds(600);
    static final Actor ACTOR = new Actor(MemberId.random());

    private static IntegrationStatus status(AiHealth health) {
        return new AiStatusSource(actor -> health, Clock.fixed(NOW, ZoneOffset.UTC)).check(ACTOR);
    }

    @Test
    void withoutCredentialsTheIntegrationIsNotConfigured() {
        assertThat(status(new AiHealth(false, null, null, null, null)).state())
                .isEqualTo(IntegrationStatus.NOT_CONFIGURED);
    }

    @Test
    void credentialsAloneAreNotReportedAsConnected() {
        IntegrationStatus status = status(new AiHealth(true, null, null, null, null));

        assertThat(status.state()).isEqualTo(IntegrationStatus.DEGRADED);
        assertThat(status.detail()).contains("not verified");
    }

    @Test
    void providerFailureDegradesButAWorkloadFailureDoesNot() {
        assertThat(status(new AiHealth(true, EARLIER, "FAILED", "AUTHENTICATION", NOW)).state())
                .isEqualTo(IntegrationStatus.DEGRADED);
        assertThat(status(new AiHealth(true, EARLIER, "FAILED", "BUDGET_EXHAUSTED", NOW)).state())
                .isEqualTo(IntegrationStatus.CONNECTED);
        IntegrationStatus connected = status(new AiHealth(true, EARLIER, "SUCCEEDED", null, EARLIER));
        assertThat(connected.state()).isEqualTo(IntegrationStatus.CONNECTED);
        assertThat(connected.lastSuccessAt()).isEqualTo(EARLIER);
    }
}
