package dev.aegis.operations.adapters.sources;

import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.operations.application.ports.HealthCheckSource;
import dev.aegis.portability.application.api.ExportQueries;
import dev.aegis.portability.application.api.ExportStatus;
import dev.aegis.shared.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The daily export (ADR-0029). The line is fine while a run finished within the last two days and none failed since;
 * its date is when the export was last confirmed current and {@code generatedAt} when the newest generation was
 * written, which is older whenever the data did not change. Before the first run the line is unchecked.
 */
@Component
public class ExportStatusSource implements HealthCheckSource {
    static final String AREA = "export";
    /** Two daily intervals, as for a stale recurring job (ADR-0013). */
    static final Duration STALE_AFTER = Duration.ofDays(2).plusMinutes(10);

    private final ExportQueries exports;
    private final Clock clock;

    public ExportStatusSource(ExportQueries exports, Clock clock) {
        this.exports = exports;
        this.clock = clock;
    }

    @Override
    public String area() {
        return AREA;
    }

    @Override
    public int order() {
        return 32;
    }

    @Override
    public Optional<HealthCheck> check(Actor actor) {
        ExportStatus status = exports.status(actor);
        Map<String, String> params = new HashMap<>();
        if (status.latest() != null) {
            params.put("generatedAt", status.latest().createdAt().toString());
        }
        Instant checked = status.lastCheckedAt();
        Instant failed = status.lastFailedAt();
        if (failed != null && (checked == null || failed.isAfter(checked))) {
            return Optional.of(HealthCheck.problem(AREA, "export.failed", params, checked));
        }
        if (checked == null) {
            return Optional.of(new HealthCheck(AREA, HealthCheck.UNCHECKED, "export.pending", params, null));
        }
        if (checked.isBefore(clock.instant().minus(STALE_AFTER))) {
            return Optional.of(HealthCheck.problem(AREA, "export.stale", params, checked));
        }
        return Optional.of(new HealthCheck(AREA, HealthCheck.OK, "export.ok", params, checked));
    }
}
