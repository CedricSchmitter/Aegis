package dev.aegis.operations.adapters.sources;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.assistant.application.api.AiHealthQuery;
import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.shared.Actor;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * AI provider status from the recorded runs, judged per configured model. Without credentials the integration is not
 * configured. With credentials it is connected only when every configured model's latest outcome is a success or a
 * failure that is not the provider's fault; a model whose latest run failed for a provider reason, or that was never
 * verified, degrades it. Budget, output or permission failures of a single run do not degrade the integration.
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
        List<String> failing = ai.models().stream()
                .filter(model -> model.lastFailureCategory() != null
                        && PROVIDER_FAILURES.contains(model.lastFailureCategory()))
                .map(model -> model.model() + ": " + model.lastFailureCategory()).toList();
        if (!failing.isEmpty()) {
            return new IntegrationStatus("ai", IntegrationStatus.DEGRADED, "latest run failed for "
                    + String.join(", ", failing), ai.lastSuccessAt(), clock.instant());
        }
        List<String> unverified = ai.models().stream().filter(model -> model.lastState() == null)
                .map(AiHealth.ModelHealth::model).toList();
        if (!unverified.isEmpty() || ai.lastSuccessAt() == null) {
            return new IntegrationStatus("ai", IntegrationStatus.DEGRADED,
                    "credentials present; not verified yet for " + String.join(", ", unverified)
                            + "; run the connection check", ai.lastSuccessAt(), clock.instant());
        }
        return new IntegrationStatus("ai", IntegrationStatus.CONNECTED,
                "every configured model answered its latest run", ai.lastSuccessAt(), clock.instant());
    }
}
