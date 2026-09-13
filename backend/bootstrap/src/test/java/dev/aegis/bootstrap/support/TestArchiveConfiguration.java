package dev.aegis.bootstrap.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the HTTP Paperless adapter with the in-process fake for integration tests. */
@TestConfiguration
public class TestArchiveConfiguration {
    @Bean
    @Primary
    FakePaperlessArchive fakePaperlessArchive() {
        return new FakePaperlessArchive();
    }
}
