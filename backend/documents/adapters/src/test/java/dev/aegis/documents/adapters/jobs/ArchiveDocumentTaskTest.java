package dev.aegis.documents.adapters.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ArchiveDocumentTaskTest {
    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(ArchiveDocumentTask.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ArchiveDocumentTask.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(ArchiveDocumentTask.backoff(4)).isEqualTo(Duration.ofMinutes(4));
        assertThat(ArchiveDocumentTask.backoff(12)).isEqualTo(Duration.ofMinutes(30));
        assertThat(ArchiveDocumentTask.backoff(100)).isEqualTo(Duration.ofMinutes(30));
    }
}
