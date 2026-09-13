package dev.aegis.operations.adapters.sources;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.portability.application.api.ExportQueries;
import dev.aegis.shared.Actor;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Last successful export generation through the portability public contract. */
@Component
public class ExportStatusSource implements IntegrationStatusSource {
    private final ExportQueries exports;
    private final Clock clock;

    public ExportStatusSource(ExportQueries exports, Clock clock) {
        this.exports = exports;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public IntegrationStatus check(Actor actor) {
        return exports.latest(actor)
                .map(latest -> new IntegrationStatus("export", IntegrationStatus.CONNECTED,
                        "generation " + latest.generation() + " (" + latest.documentCount() + " documents, "
                                + latest.taskCount() + " tasks)", latest.createdAt(), clock.instant()))
                .orElseGet(() -> new IntegrationStatus("export", IntegrationStatus.DEGRADED,
                        "no export generation yet", null, clock.instant()));
    }
}
