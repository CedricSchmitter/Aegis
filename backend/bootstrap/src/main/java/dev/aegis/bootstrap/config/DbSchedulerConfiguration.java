package dev.aegis.bootstrap.config;

import com.github.kagkarlsson.scheduler.boot.autoconfigure.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * db-scheduler wiring: JSON payloads (stable ids plus schema version, no Java serialization) and a
 * transaction-aware data source so job rows commit or roll back together with the business change.
 */
@Configuration
public class DbSchedulerConfiguration {

    @Bean
    DbSchedulerCustomizer dbSchedulerCustomizer(DataSource dataSource) {
        Serializer serializer = new Jackson3Serializer();
        DataSource transactional = new TransactionAwareDataSourceProxy(dataSource);
        return new DbSchedulerCustomizer() {
            @Override
            public Optional<Serializer> serializer() {
                return Optional.of(serializer);
            }

            @Override
            public Optional<DataSource> dataSource() {
                return Optional.of(transactional);
            }
        };
    }
}
