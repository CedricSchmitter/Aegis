package dev.aegis.bootstrap.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One injectable clock in the household time zone; tests replace it with a fixed clock. */
@Configuration
public class ClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock(@Value("${aegis.household.zone:Europe/Berlin}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
