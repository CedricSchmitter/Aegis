package dev.aegis.operations.adapters.sources;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.assistant.application.api.AiHealthQuery;
import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.operations.application.ports.HealthCheckSource;
import dev.aegis.shared.Actor;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * AI provider status from the recorded runs, judged per configured model. Without credentials AI is not set up and
 * not listed. A selected model without credentials, or whose latest run failed for a provider reason, is a problem.
 * A model without a run since the application started or its credentials changed is unchecked: a neutral state until
 * the next run or a connection check, never a problem by itself. Budget, output or permission failures of a single run
 * do not make the integration a problem.
 */
@Component
public class AiStatusSource implements HealthCheckSource {
    static final String AREA = "ai";
    static final Set<String> PROVIDER_FAILURES = Set.of("NOT_CONFIGURED", "AUTHENTICATION", "MODEL_UNAVAILABLE",
            "INVALID_REQUEST", "RATE_LIMITED", "PROVIDER_UNAVAILABLE", "TIMEOUT");

    private final AiHealthQuery health;

    public AiStatusSource(AiHealthQuery health) {
        this.health = health;
    }

    @Override
    public String area() {
        return AREA;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public Optional<HealthCheck> check(Actor actor) {
        AiHealth ai = health.health(actor);
        if (!ai.configured()) {
            return Optional.empty();
        }
        Optional<AiHealth.ModelHealth> missingCredentials = ai.models().stream()
                .filter(model -> !model.credentialsAvailable()).findFirst();
        if (missingCredentials.isPresent()) {
            return Optional.of(HealthCheck.problem(AREA, "ai.credentialsMissing",
                    Map.of("provider", missingCredentials.get().provider()), ai.lastSuccessAt()));
        }
        Optional<AiHealth.ModelHealth> failing = ai.models().stream()
                .filter(model -> model.lastFailureCategory() != null
                        && PROVIDER_FAILURES.contains(model.lastFailureCategory())).findFirst();
        if (failing.isPresent()) {
            return Optional.of(HealthCheck.problem(AREA, "ai.providerFailure",
                    Map.of("failure", failing.get().lastFailureCategory()), ai.lastSuccessAt()));
        }
        boolean unverified = ai.models().stream().anyMatch(model -> model.lastState() == null);
        if (unverified || ai.lastSuccessAt() == null) {
            return Optional.of(new HealthCheck(AREA, HealthCheck.UNCHECKED, "ai.unchecked", Map.of(),
                    ai.lastSuccessAt()));
        }
        return Optional.of(HealthCheck.ok(AREA, "ai.connected", ai.lastSuccessAt()));
    }
}
