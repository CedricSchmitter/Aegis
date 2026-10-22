package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.PurgeUseCases.FailureDecision;
import dev.aegis.documents.application.PurgeUseCases.Finished;
import dev.aegis.documents.application.PurgeUseCases.Purge;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.PurgeView;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.ProcessingFailure;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.PurgeConflictException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.Trash;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Permanent deletion of trashed documents (ADR-0021): the member command and the job's steps. */
class DocumentPurgeServiceTest {
    private CatalogFixture f;
    private DocumentCommandService commands;
    private DocumentPurgeService purges;
    private final TestDoubles.RecordingEvents events = new TestDoubles.RecordingEvents();

    @BeforeEach
    void setUp() {
        f = new CatalogFixture();
        List<Document> analysisTriggers = new ArrayList<>();
        commands = new DocumentCommandService(f.documents, f.scheduler, f.presenter, analysisTriggers::add,
                f.persons, f.access, Trash.RecoveryWindow.DEFAULT, f.clock, f.recorder);
        purges = new DocumentPurgeService(f.documents, events, ArchivalSettings.defaults(), f.clock);
    }

    private Document archived(String title, int paperlessId) {
        Document document = f.document(title);
        document.markArchived(paperlessId, CatalogFixture.NOW);
        f.documents.save(document);
        return document;
    }

    private Document trashed(Document document) {
        commands.moveToTrash(f.actor, document.id(), document.version());
        return f.documents.findById(document.id()).orElseThrow();
    }

    @Test
    void onlyATrashedDocumentWhoseArchivalSettledCanBeDeleted() {
        Document visible = archived("Rechnung", 11);
        Document uploading = trashed(f.document("Noch im Upload"));

        assertThatThrownBy(() -> commands.purge(f.actor, visible.id(), visible.version()))
                .isInstanceOf(PurgeConflictException.class)
                .hasFieldOrPropertyWithValue("code", "document.purge.notTrashed");
        assertThatThrownBy(() -> commands.purge(f.actor, uploading.id(), uploading.version()))
                .isInstanceOf(PurgeConflictException.class)
                .hasFieldOrPropertyWithValue("code", "document.purge.archiving");
        assertThat(f.scheduler.purges).isEmpty();
    }

    @Test
    void aRequestedDeletionSchedulesTheJobAndCanNoLongerBeRestored() {
        Document document = trashed(archived("Falscher Scan", 12));
        MemberId cedric = f.cedric;

        DocumentSummary pending = commands.purge(new Actor(cedric), document.id(), document.version()).orElseThrow();

        assertThat(pending.purge()).isEqualTo(new PurgeView(PurgeView.PENDING, cedric, CatalogFixture.NOW, null,
                null));
        assertThat(f.scheduler.purges).containsExactly(document.id());
        assertThatThrownBy(() -> commands.restoreFromTrash(f.actor, document.id(), document.version()))
                .isInstanceOf(PurgeConflictException.class)
                .hasFieldOrPropertyWithValue("code", "document.purge.pending");
        // A retried request after a lost response carries an old version and answers with the pending deletion.
        assertThat(commands.purge(f.actor, document.id(), document.version() + 7)).get()
                .satisfies(summary -> assertThat(summary.purge().requestedBy()).isEqualTo(cedric));
    }

    @Test
    void theRequestIsVersionCheckedAndLimitedToMembers() {
        Document document = trashed(archived("Kontoauszug", 13));

        assertThatThrownBy(() -> commands.purge(f.actor, document.id(), document.version() + 1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> commands.purge(new Actor(MemberId.random()), document.id(), document.version()))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> commands.purge(f.actor, dev.aegis.shared.DocumentId.random(), 0))
                .isInstanceOf(NotFoundException.class);
        assertThat(f.documents.findById(document.id()).orElseThrow().purge()).isEmpty();
    }

    @Test
    void theJobDeletesTheArchiveDocumentTheStagingCopyAndTheRecordAndThenAnswersEmpty() {
        Document document = trashed(archived("Mietvertrag Entwurf", 14));
        commands.purge(f.actor, document.id(), document.version());

        assertThat(purges.prepare(document.id())).isEqualTo(new Purge(document.id(), 14, null,
                document.stagingPath()));
        purges.complete(document.id());

        assertThat(f.documents.findById(document.id())).isEmpty();
        assertThat(f.documents.wasPurged(document.id())).isTrue();
        assertThat(events.events).containsExactly("purged:" + document.id());
        assertThat(purges.prepare(document.id())).isInstanceOf(Finished.class);
        // A repeated member request after the deletion is harmless and says that the document is gone.
        assertThat(commands.purge(f.actor, document.id(), 0)).isEmpty();
        purges.complete(document.id());
        assertThat(events.events).hasSize(1);
    }

    @Test
    void anArchiveDocumentAnotherDocumentStillUsesIsKept() {
        Document document = trashed(archived("Doppelt", 15));
        archived("Anderer Datensatz", 15);
        commands.purge(f.actor, document.id(), document.version());

        assertThat(purges.prepare(document.id())).isEqualTo(new Purge(document.id(), null, null,
                document.stagingPath()));
    }

    @Test
    void aFailedArchivalNamesItsUnrecordedConsumptionTaskSoALateArchiveDocumentIsDeletedToo() {
        Document failed = f.document("Zeitüberschreitung");
        failed.markSubmitted("task-7", CatalogFixture.NOW);
        failed.recordFailure(new ProcessingFailure("paperless.timeout", "late", CatalogFixture.NOW), false, 8);
        f.documents.save(failed);
        Document document = trashed(failed);
        commands.purge(f.actor, document.id(), document.version());

        assertThat(purges.prepare(document.id())).isEqualTo(new Purge(document.id(), null, "task-7",
                document.stagingPath()));
    }

    @Test
    void retryableFailuresAreBoundedAndAFailedDeletionCanBeRequestedAgain() {
        Document document = trashed(archived("Paperless weg", 16));
        commands.purge(f.actor, document.id(), document.version());
        int max = ArchivalSettings.defaults().maxAttempts();

        for (int attempt = 1; attempt < max; attempt++) {
            assertThat(purges.recordFailure(document.id(), "paperless.unavailable", true))
                    .isEqualTo(new FailureDecision(true, attempt));
        }
        assertThat(purges.recordFailure(document.id(), "paperless.unavailable", true))
                .isEqualTo(new FailureDecision(false, max));
        Document failed = f.documents.findById(document.id()).orElseThrow();
        assertThat(failed.purge().orElseThrow().failureCode()).isEqualTo("paperless.unavailable");
        assertThat(purges.prepare(document.id())).isInstanceOf(Finished.class);
        purges.complete(document.id());
        assertThat(f.documents.findById(document.id())).isPresent();
        // Still not restorable: the archive may already have lost the original.
        assertThatThrownBy(() -> commands.restoreFromTrash(f.actor, document.id(), failed.version()))
                .isInstanceOf(PurgeConflictException.class);

        DocumentSummary again = commands.purge(f.actor, document.id(), failed.version()).orElseThrow();
        assertThat(again.purge().state()).isEqualTo(PurgeView.PENDING);
        assertThat(again.purge().failureCode()).isNull();
        assertThat(purges.prepare(document.id())).isInstanceOf(Purge.class);
    }

    @Test
    void aRefusalFailsAtOnceAndAFinishedPurgeIgnoresLateFailures() {
        Document document = trashed(archived("Verboten", 17));
        commands.purge(f.actor, document.id(), document.version());

        assertThat(purges.recordFailure(document.id(), "paperless.rejected", false))
                .isEqualTo(new FailureDecision(false, 1));
        assertThat(purges.recordFailure(dev.aegis.shared.DocumentId.random(), "paperless.unavailable", true))
                .isEqualTo(new FailureDecision(false, 0));
    }

    @Test
    void reconciliationReschedulesARequestedDeletionWhoseJobWasLost() throws Exception {
        Document document = trashed(archived("Verlorener Job", 18));
        Document pending = f.documents.findById(document.id()).orElseThrow();
        pending.requestPurge(f.lisa, CatalogFixture.NOW);
        f.documents.save(pending);
        ReconciliationService reconciliation = new ReconciliationService(f.documents, new InMemoryOriginalStore(),
                new TestDoubles.FakeArchive(), f.scheduler, ArchivalSettings.defaults(), f.clock);

        assertThat(reconciliation.reconcile().rescheduled()).isEqualTo(1);
        assertThat(f.scheduler.purges).containsExactly(document.id());
        assertThat(reconciliation.reconcile().rescheduled()).isZero();
    }
}
