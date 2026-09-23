package dev.aegis.documents.adapters.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.aegis.documents.application.ArchiveTextService;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.shared.DocumentId;
import java.lang.reflect.Proxy;
import java.time.Clock;
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

    @Test
    void aTextCheckThatBreaksNeverFailsTheFinishedArchivalJob() {
        DocumentRepository broken = (DocumentRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DocumentRepository.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("database unavailable");
                });
        ArchiveTextService textChecks = new ArchiveTextService(broken, null, null, 1, Clock.systemUTC());
        ArchiveDocumentTask task = new ArchiveDocumentTask(null, textChecks, null, null, null, Clock.systemUTC());

        assertThatCode(() -> task.checkText(DocumentId.random())).doesNotThrowAnyException();
    }
}
