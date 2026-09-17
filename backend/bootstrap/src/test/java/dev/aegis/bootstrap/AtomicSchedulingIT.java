package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.adapters.jobs.ArchiveDocumentTask;
import dev.aegis.documents.application.RegisterReceivedDocument;
import dev.aegis.documents.application.RegisterReceivedDocument.RegistrationCommand;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.Subject;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the persistence and job guarantees against PostgreSQL: atomic record/job creation, resumption from
 * persisted state after an interruption, and safe repeated execution.
 */
class AtomicSchedulingIT extends PostgresIT {
    @Autowired
    RegisterReceivedDocument registrar;
    @Autowired
    DocumentRepository documents;
    @Autowired
    OriginalStore store;
    @Autowired
    ProcessingScheduler scheduler;
    @Autowired
    SchedulerClient schedulerClient;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    FakePaperlessArchive archive;

    private MemberId anna() {
        return members.findCredentials(ANNA).orElseThrow().id();
    }

    private RegistrationCommand command(DocumentId id, String stagingPath, long size, String checksumSeed) {
        return new RegistrationCommand(id, "Atomic " + id, "atomic.pdf", "application/pdf", size,
                dev.aegis.documents.domain.Checksum.of(checksumSeed), anna(), Instant.now(),
                Set.of(Subject.household()), stagingPath, null);
    }

    @Test
    void recordAndJobRollBackTogetherWhenTheTransactionFails() {
        DocumentId id = DocumentId.random();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            registrar.register(command(id, "missing.pdf", 10, "1".repeat(64)));
            assertThat(documents.findById(id)).as("visible inside the transaction").isPresent();
            throw new IllegalStateException("simulated crash after scheduling");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(documents.findById(id)).isEmpty();
        assertThat(schedulerClient.getScheduledExecution(ArchiveDocumentTask.DESCRIPTOR.instanceId(id.toString())))
                .isEmpty();
    }

    @Test
    void committedRegistrationCreatesTheJobWhichArchivesTheDocument() throws Exception {
        DocumentId id = DocumentId.random();
        byte[] pdf = ("%PDF-1.4 atomic " + id + "\n").getBytes(StandardCharsets.US_ASCII);
        var stored = store.store(id, new ByteArrayInputStream(pdf));

        var result = registrar.register(command(id, stored.stagingPath(), stored.sizeBytes(),
                stored.checksum().sha256Hex()));

        assertThat(result.duplicate()).isFalse();
        assertThat(documents.findById(id)).isPresent();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                documents.findById(id).orElseThrow().state()).isEqualTo(ProcessingState.ARCHIVED));
        assertThat(schedulerClient.getScheduledExecution(ArchiveDocumentTask.DESCRIPTOR.instanceId(id.toString())))
                .as("job removed after completion").isEmpty();
    }

    @Test
    void interruptedSubmissionResumesFromPersistedStateWithoutResubmitting() throws Exception {
        DocumentId id = DocumentId.random();
        byte[] pdf = ("%PDF-1.4 resumed " + id + "\n").getBytes(StandardCharsets.US_ASCII);
        var stored = store.store(id, new ByteArrayInputStream(pdf));
        String taskId = archive.registerCompletedTask(pdf, "Resumed");
        Document document = Document.received(id, "Resumed", "resumed.pdf", "application/pdf", stored.sizeBytes(),
                stored.checksum(), anna(), Instant.now(), Set.of(Subject.house()), stored.stagingPath());
        document.markSubmitted(taskId, Instant.now());
        // Simulates a crash after Paperless accepted the file and the task id was recorded: only the state is left.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> documents.save(document));
        int submissionsBefore = archive.submissions.get();

        scheduler.scheduleArchival(id);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                documents.findById(id).orElseThrow().state()).isEqualTo(ProcessingState.ARCHIVED));
        assertThat(archive.submissions.get()).as("no resubmission").isEqualTo(submissionsBefore);
    }

    @Test
    void schedulingTwiceForTheSameDocumentIsIdempotent() throws Exception {
        DocumentId id = DocumentId.random();
        byte[] pdf = ("%PDF-1.4 twice " + id + "\n").getBytes(StandardCharsets.US_ASCII);
        var stored = store.store(id, new ByteArrayInputStream(pdf));
        registrar.register(command(id, stored.stagingPath(), stored.sizeBytes(), stored.checksum().sha256Hex()));

        scheduler.scheduleArchival(id);
        scheduler.scheduleArchival(id);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                documents.findById(id).orElseThrow().state()).isEqualTo(ProcessingState.ARCHIVED));
        assertThat(documents.findById(id).orElseThrow().attempts()).isZero();
    }
}
