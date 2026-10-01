package dev.aegis.documents.adapters.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.TaskInstance;
import dev.aegis.documents.application.ArchivalSettings;
import dev.aegis.documents.application.PurgeUseCases;
import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.shared.DocumentId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order and failure handling of the purge job (ADR-0021): outside work first, the record last, and nothing
 * deleted in the database while the archive outcome is uncertain. PostgreSQL and the scheduler are covered by PurgeIT.
 */
class PurgeDocumentTaskTest {
    private final DocumentId id = DocumentId.random();
    private final List<String> calls = new ArrayList<>();
    private PurgeUseCases.PurgeStep step = new PurgeUseCases.Purge(id, 41, null, id + ".pdf");
    private RuntimeException archiveFailure;
    private PaperlessArchive.TaskOutcome taskOutcome = new PaperlessArchive.Pending();
    private boolean shared;
    private IOException stagingFailure;

    private PurgeDocumentTask task() {
        PurgeUseCases purges = new PurgeUseCases() {
            @Override
            public PurgeStep prepare(DocumentId documentId) {
                return step;
            }

            @Override
            public boolean archiveDocumentShared(DocumentId documentId, int paperlessDocumentId) {
                return shared;
            }

            @Override
            public FailureDecision recordFailure(DocumentId documentId, String code, boolean retryable) {
                calls.add("failure:" + code + ":" + retryable);
                return new FailureDecision(retryable, 1);
            }

            @Override
            public void complete(DocumentId documentId) {
                calls.add("complete");
            }
        };
        PaperlessArchive archive = (PaperlessArchive) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PaperlessArchive.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "purge" -> {
                        if (archiveFailure != null) {
                            throw archiveFailure;
                        }
                        calls.add("archive:" + args[0]);
                        yield null;
                    }
                    case "checkTask" -> taskOutcome;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OriginalStore store = (OriginalStore) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {OriginalStore.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("delete")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    if (stagingFailure != null) {
                        throw stagingFailure;
                    }
                    calls.add("staging:" + args[0]);
                    return null;
                });
        return new PurgeDocumentTask(purges, archive, store, ArchivalSettings.defaults(), Clock.systemUTC());
    }

    private void run() {
        PurgeDocumentPayload payload = PurgeDocumentPayload.of(id.toString());
        task().run(new TaskInstance<>(PurgeDocumentTask.DESCRIPTOR.getTaskName(), id.toString(), payload), null);
    }

    @Test
    void theArchiveDocumentAndTheStagingCopyGoBeforeTheRecord() {
        run();

        assertThat(calls).containsExactly("archive:41", "staging:" + id + ".pdf", "complete");
    }

    @Test
    void anUnreachableArchiveIsRetriedAndTheRecordStays() {
        archiveFailure = new ArchiveUnavailableException("timeout");

        run();

        assertThat(calls).containsExactly("failure:paperless.unavailable:true");
    }

    @Test
    void aRefusalIsRecordedAsFinalFailure() {
        archiveFailure = new ArchiveRejectedException("paperless.rejected", "403");

        run();

        assertThat(calls).containsExactly("failure:paperless.rejected:false");
    }

    @Test
    void aStagingCopyThatCannotBeDeletedKeepsThePurgePending() {
        stagingFailure = new IOException("read-only file system");

        run();

        assertThat(calls).containsExactly("archive:41", "failure:document.stagingDeleteFailed:true");
    }

    @Test
    void anArchiveDocumentCreatedLateFromAFailedArchivalIsDeletedUnlessAnotherRecordUsesIt() {
        step = new PurgeUseCases.Purge(id, null, "task-9", id + ".pdf");
        taskOutcome = new PaperlessArchive.Completed(77);
        run();
        assertThat(calls).containsExactly("archive:77", "staging:" + id + ".pdf", "complete");

        calls.clear();
        shared = true;
        run();
        assertThat(calls).containsExactly("staging:" + id + ".pdf", "complete");

        calls.clear();
        taskOutcome = new PaperlessArchive.Duplicate(5);
        run();
        assertThat(calls).containsExactly("staging:" + id + ".pdf", "complete");
    }

    @Test
    void aConsumptionStillRunningInTheArchiveDelaysTheDeletion() {
        step = new PurgeUseCases.Purge(id, null, "task-9", id + ".pdf");
        taskOutcome = new PaperlessArchive.Pending();

        run();

        assertThat(calls).containsExactly("failure:paperless.consumptionPending:true");
    }

    @Test
    void aFinishedStepDoesNothing() {
        step = new PurgeUseCases.Finished(id, "record gone");

        run();

        assertThat(calls).isEmpty();
    }
}
