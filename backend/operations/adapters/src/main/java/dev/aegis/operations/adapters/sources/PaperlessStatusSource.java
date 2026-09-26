package dev.aegis.operations.adapters.sources;

import dev.aegis.documents.application.api.ArchiveHealth;
import dev.aegis.documents.application.api.DocumentStatusQuery;
import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.operations.application.ports.HealthCheckSource;
import dev.aegis.shared.Actor;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Paperless archive status through the documents public contract; its last success is the last successful
 * archival.
 */
@Component
public class PaperlessStatusSource implements HealthCheckSource {
    static final String AREA = "archive";

    private final DocumentStatusQuery documents;

    public PaperlessStatusSource(DocumentStatusQuery documents) {
        this.documents = documents;
    }

    @Override
    public String area() {
        return AREA;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<HealthCheck> check(Actor actor) {
        ArchiveHealth health = documents.archiveHealth(actor);
        return Optional.of(health.reachable()
                ? HealthCheck.ok(AREA, "archive.reachable", health.lastSuccessfulArchivalAt())
                : HealthCheck.problem(AREA, "archive.unavailable", Map.of(), health.lastSuccessfulArchivalAt()));
    }
}
