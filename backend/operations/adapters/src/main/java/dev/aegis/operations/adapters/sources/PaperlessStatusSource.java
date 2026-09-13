package dev.aegis.operations.adapters.sources;

import dev.aegis.documents.application.api.ArchiveHealth;
import dev.aegis.documents.application.api.DocumentStatusQuery;
import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.shared.Actor;
import org.springframework.stereotype.Component;

/** Paperless archive status through the documents public contract. */
@Component
public class PaperlessStatusSource implements IntegrationStatusSource {
    private final DocumentStatusQuery documents;

    public PaperlessStatusSource(DocumentStatusQuery documents) {
        this.documents = documents;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public IntegrationStatus check(Actor actor) {
        ArchiveHealth health = documents.archiveHealth(actor);
        String detail = health.reachable() ? (health.serverVersion() == null ? null
                : "Paperless-ngx " + health.serverVersion()) : health.detail();
        return new IntegrationStatus("paperless", health.reachable() ? IntegrationStatus.CONNECTED
                : IntegrationStatus.UNAVAILABLE, detail, health.lastSuccessfulArchivalAt(), health.checkedAt());
    }
}
