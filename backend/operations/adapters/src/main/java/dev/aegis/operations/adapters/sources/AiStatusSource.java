package dev.aegis.operations.adapters.sources;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.assistant.application.api.AiHealthQuery;
import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.shared.Actor;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * AI provider status from the recorded runs. Without credentials the integration is reported as not configured;
 * with credentials it is connected only after a successful run, and degraded while the latest finished run failed
 * for a provider reason. Budget, output or permission failures of a single run do not degrade the integration.
 */
@Component
public class AiStatusSource implements IntegrationStatusSource {
    static final Set<String> PROVIDER_FAILURES = Set.of("NOT_CONFIGURED", "AUTHENTICATION", "MODEL_UNAVAILABLE",
            "INVALID_REQUEST", "RATE_LIMITED", "PROVIDER_UNAVAILABLE", "TIMEOUT");

    private final AiHealthQuery health;
    private final Clock clock;

    public AiStatusSource(AiHealthQuery health, Clock clock) {
        this.health = health;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public IntegrationStatus check(Actor actor) {
        AiHealth ai = health.health(actor);
        if (!ai.configured()) {
            return IntegrationStatus.notConfigured("ai",
                    "no AI provider credentials; capture, search and planning work without AI");
        }
        if ("FAILED".equals(ai.lastFinishedState()) && PROVIDER_FAILURES.contains(ai.lastFailureCategory())) {
            return new IntegrationStatus("ai", IntegrationStatus.DEGRADED,
                    "latest run failed: " + ai.lastFailureCategory(), ai.lastSuccessAt(), clock.instant());
        }
        if (ai.lastSuccessAt() == null) {
            return new IntegrationStatus("ai", IntegrationStatus.DEGRADED,
                    "credentials present but not verified; run the connection check", null, clock.instant());
        }
        return new IntegrationStatus("ai", IntegrationStatus.CONNECTED, "provider answered the latest verified run",
                ai.lastSuccessAt(), clock.instant());
    }
}
