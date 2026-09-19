package dev.aegis.bootstrap.support;

import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.ProposeClassification;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces external integrations for integration tests: the HTTP Paperless adapter by an in-process fake, and the
 * model provider by a controlled gateway with a test review workload. All integration tests share this one
 * Spring context, so only one scheduler polls the test database.
 */
@TestConfiguration
public class TestArchiveConfiguration {
    @Bean
    @Primary
    FakePaperlessArchive fakePaperlessArchive() {
        return new FakePaperlessArchive();
    }

    @Bean
    ControlledModelGateway controlledModelGateway() {
        return new ControlledModelGateway();
    }

    @Bean
    TestReviewWorkload testReviewWorkload(DocumentQueries documents, ProposeClassification proposals) {
        return new TestReviewWorkload(documents, proposals);
    }
}
