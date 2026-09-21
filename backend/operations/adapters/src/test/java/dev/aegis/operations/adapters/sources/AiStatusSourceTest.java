package dev.aegis.operations.adapters.sources;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.assistant.application.api.AiHealth.ModelHealth;
import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiStatusSourceTest {
    static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    static final Instant EARLIER = NOW.minusSeconds(600);
    static final Actor ACTOR = new Actor(MemberId.random());
    static final ModelHealth HAIKU_OK = new ModelHealth("anthropic", "claude-haiku-4-5", true, "SUCCEEDED", null,
            EARLIER);

    private static IntegrationStatus status(AiHealth health) {
        return new AiStatusSource(actor -> health, Clock.fixed(NOW, ZoneOffset.UTC)).check(ACTOR);
    }

    @Test
    void withoutCredentialsTheIntegrationIsNotConfigured() {
        assertThat(status(new AiHealth(false, null, List.of())).state()).isEqualTo(IntegrationStatus.NOT_CONFIGURED);
    }

    @Test
    void credentialsAloneOrAnUncheckedModelAreNotReportedAsConnected() {
        IntegrationStatus none = status(new AiHealth(true, null, List.of(
                new ModelHealth("anthropic", "claude-haiku-4-5", true, null, null, null))));
        IntegrationStatus partly = status(new AiHealth(true, EARLIER, List.of(HAIKU_OK,
                new ModelHealth("anthropic", "claude-opus-5", true, null, null, null))));

        assertThat(none.state()).isEqualTo(IntegrationStatus.DEGRADED);
        assertThat(partly.state()).isEqualTo(IntegrationStatus.DEGRADED);
        assertThat(partly.detail()).contains("not verified").contains("claude-opus-5");
    }

    @Test
    void historicalSuccessCannotHideMissingSelectedCredentials() {
        IntegrationStatus current = status(new AiHealth(true, EARLIER, List.of(
                new ModelHealth("anthropic", "claude-sonnet-5", false, "SUCCEEDED", null, EARLIER))));

        assertThat(current.state()).isEqualTo(IntegrationStatus.DEGRADED);
        assertThat(current.detail()).contains("credentials missing").contains("anthropic/claude-sonnet-5");
    }

    @Test
    void oneUnavailableModelDegradesTheIntegrationEvenAfterAnotherSucceeded() {
        IntegrationStatus status = status(new AiHealth(true, EARLIER, List.of(HAIKU_OK,
                new ModelHealth("anthropic", "claude-opus-5", true, "FAILED", "MODEL_UNAVAILABLE", NOW))));

        assertThat(status.state()).isEqualTo(IntegrationStatus.DEGRADED);
        assertThat(status.detail()).contains("claude-opus-5: MODEL_UNAVAILABLE");
        assertThat(status.lastSuccessAt()).isEqualTo(EARLIER);
    }

    @Test
    void workloadFailuresDoNotDegradeAndVerifiedModelsAreConnected() {
        assertThat(status(new AiHealth(true, EARLIER, List.of(HAIKU_OK,
                new ModelHealth("anthropic", "claude-opus-5", true, "FAILED", "BUDGET_EXHAUSTED", NOW)))).state())
                .isEqualTo(IntegrationStatus.CONNECTED);
        assertThat(status(new AiHealth(true, EARLIER, List.of(HAIKU_OK))).state())
                .isEqualTo(IntegrationStatus.CONNECTED);
    }
}
