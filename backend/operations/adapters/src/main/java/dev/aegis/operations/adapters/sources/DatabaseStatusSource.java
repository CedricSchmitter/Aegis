package dev.aegis.operations.adapters.sources;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.shared.Actor;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Live database probe (connection validity), independent of cached health. */
@Component
public class DatabaseStatusSource implements IntegrationStatusSource {
    private final DataSource dataSource;
    private final Clock clock;

    public DatabaseStatusSource(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public IntegrationStatus check(Actor actor) {
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return new IntegrationStatus("database", valid ? IntegrationStatus.CONNECTED : IntegrationStatus.UNAVAILABLE,
                    valid ? "PostgreSQL " + connection.getMetaData().getDatabaseProductVersion() : "connection invalid",
                    null, clock.instant());
        } catch (SQLException e) {
            return new IntegrationStatus("database", IntegrationStatus.UNAVAILABLE, e.getMessage(), null,
                    clock.instant());
        }
    }
}
