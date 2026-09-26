package dev.aegis.operations.adapters.sources;

import dev.aegis.operations.application.api.HealthCheck;
import dev.aegis.operations.application.ports.HealthCheckSource;
import dev.aegis.shared.Actor;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Live database probe (connection validity), independent of cached health. A valid connection is a success at the
 * time of the probe; the latest one is remembered, so an outage reports when the database last answered since the
 * application started.
 */
@Component
public class DatabaseStatusSource implements HealthCheckSource {
    static final String AREA = "database";

    private final DataSource dataSource;
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();

    public DatabaseStatusSource(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public String area() {
        return AREA;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Optional<HealthCheck> check(Actor actor) {
        boolean valid;
        try (Connection connection = dataSource.getConnection()) {
            valid = connection.isValid(2);
        } catch (SQLException e) {
            valid = false;
        }
        if (valid) {
            Instant now = clock.instant();
            lastSuccess.set(now);
            return Optional.of(HealthCheck.ok(AREA, "database.connected", now));
        }
        return Optional.of(HealthCheck.problem(AREA, "database.unavailable", Map.of(), lastSuccess.get()));
    }
}
