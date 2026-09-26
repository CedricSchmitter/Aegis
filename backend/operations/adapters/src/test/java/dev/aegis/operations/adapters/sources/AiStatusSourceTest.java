package dev.aegis.operations.adapters.sources;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.assistant.application.api.AiHealth.ModelHealth;
import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AiStatusSourceTest {
    static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    static final Instant EARLIER = NOW.minusSeconds(600);
    static final Actor ACTOR = new Actor(MemberId.random());
    static final ModelHealth HAIKU_OK = new ModelHealth("anthropic", "claude-haiku-4-5", true, "SUCCEEDED", null,
            EARLIER);

    private static Optional<HealthCheck> status(AiHealth health) {
        return new AiStatusSource(actor -> health).check(ACTOR);
    }

    @Test
    void withoutCredentialsAiIsNotSetUpAndNotListed() {
        assertThat(status(new AiHealth(false, null, List.of()))).isEmpty();
    }

    @Test
    void anUncheckedModelIsNeutralNotAProblem() {
        HealthCheck afterStart = status(new AiHealth(true, null, List.of(
                new ModelHealth("anthropic", "claude-sonnet-5", true, null, null, null)))).orElseThrow();
        HealthCheck partly = status(new AiHealth(true, EARLIER, List.of(HAIKU_OK,
                new ModelHealth("anthropic", "claude-opus-5", true, null, null, null)))).orElseThrow();

        assertThat(afterStart.state()).isEqualTo(HealthCheck.UNCHECKED);
        assertThat(afterStart.reason()).isEqualTo("ai.unchecked");
        assertThat(afterStart.params()).isEmpty();
        assertThat(partly.state()).isEqualTo(HealthCheck.UNCHECKED);
        assertThat(partly.lastSuccessAt()).isEqualTo(EARLIER);
    }

    @Test
    void historicalSuccessCannotHideMissingSelectedCredentials() {
        HealthCheck current = status(new AiHealth(true, EARLIER, List.of(
                new ModelHealth("anthropic", "claude-sonnet-5", false, "SUCCEEDED", null, EARLIER)))).orElseThrow();

        assertThat(current.state()).isEqualTo(HealthCheck.PROBLEM);
        assertThat(current.reason()).isEqualTo("ai.credentialsMissing");
        assertThat(current.params()).isEqualTo(Map.of("provider", "anthropic"));
    }

    @Test
    void oneUnavailableModelIsAProblemEvenAfterAnotherSucceeded() {
        HealthCheck status = status(new AiHealth(true, EARLIER, List.of(HAIKU_OK,
                new ModelHealth("anthropic", "claude-opus-5", true, "FAILED", "MODEL_UNAVAILABLE", NOW))))
                .orElseThrow();

        assertThat(status.state()).isEqualTo(HealthCheck.PROBLEM);
        assertThat(status.reason()).isEqualTo("ai.providerFailure");
        assertThat(status.params()).isEqualTo(Map.of("failure", "MODEL_UNAVAILABLE"));
        assertThat(status.lastSuccessAt()).isEqualTo(EARLIER);
    }

    @Test
    void workloadFailuresDoNotMatterAndVerifiedModelsAreConnected() {
        assertThat(status(new AiHealth(true, EARLIER, List.of(HAIKU_OK,
                new ModelHealth("anthropic", "claude-opus-5", true, "FAILED", "BUDGET_EXHAUSTED", NOW))))
                .orElseThrow().state()).isEqualTo(HealthCheck.OK);
        assertThat(status(new AiHealth(true, EARLIER, List.of(HAIKU_OK))).orElseThrow())
                .isEqualTo(HealthCheck.ok("ai", "ai.connected", EARLIER));
    }
}
