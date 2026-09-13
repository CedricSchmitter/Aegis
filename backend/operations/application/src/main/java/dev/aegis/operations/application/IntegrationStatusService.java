package dev.aegis.operations.application;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.api.SystemStatus;
import dev.aegis.operations.application.api.SystemStatusQuery;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.operations.application.ports.ProcessingOverviewSource;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Composes the status page from independent probes; a failing probe never hides the others. */
public class IntegrationStatusService implements SystemStatusQuery {
    private final List<IntegrationStatusSource> sources;
    private final ProcessingOverviewSource processing;
    private final HouseholdAccess access;
    private final Clock clock;

    public IntegrationStatusService(List<IntegrationStatusSource> sources, ProcessingOverviewSource processing,
            HouseholdAccess access, Clock clock) {
        this.sources = sources.stream().sorted(Comparator.comparingInt(IntegrationStatusSource::order)).toList();
        this.processing = processing;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public SystemStatus status(Actor actor) {
        access.requireMember(actor);
        List<IntegrationStatus> statuses = new ArrayList<>();
        for (IntegrationStatusSource source : sources) {
            try {
                statuses.add(source.check(actor));
            } catch (RuntimeException failure) {
                statuses.add(new IntegrationStatus(source.getClass().getSimpleName(), IntegrationStatus.UNAVAILABLE,
                        "probe failed: " + failure.getMessage(), null, clock.instant()));
            }
        }
        return new SystemStatus(statuses, processing.processingCounts(actor), clock.instant());
    }
}
