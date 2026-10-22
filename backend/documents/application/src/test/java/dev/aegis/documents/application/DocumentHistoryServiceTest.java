package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.DocumentHistory.DocumentChangeView;
import dev.aegis.documents.application.api.DocumentHistory.DocumentHistoryPage;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.domain.Document;
import dev.aegis.shared.Actor;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import dev.aegis.shared.UndoConflictException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentHistoryServiceTest {
    private final CatalogFixture f = new CatalogFixture();
    private final Actor asCedric = new Actor(f.cedric);
    private DocumentCommandService commands;
    private DocumentHistoryService history;

    @BeforeEach
    void setUp() {
        commands = new DocumentCommandService(f.documents, f.scheduler, f.presenter, id -> { }, f.persons, f.access,
                Trash.RecoveryWindow.DEFAULT, f.clock, f.recorder);
        history = new DocumentHistoryService(f.documents, f.classifications, f.analyses, f.categories, f.labels,
                f.placements, f.history, f.recorder, f.lock, f.presenter, f.access, f.clock);
    }

    private DocumentHistoryPage page(Document document) {
        return history.history(asCedric, document.id(), null, 50);
    }

    private DocumentChangeView latest(Document document) {
        return page(document).changes().getFirst();
    }

    private DocumentSummary labels(Document document, LabelView... labels) {
        return f.classificationService.setLabels(f.actor, document.id(),
                java.util.Arrays.stream(labels).map(LabelView::id).toList(),
                f.classificationVersion(document.id()));
    }

    private static List<UUID> labelIds(DocumentSummary summary) {
        return summary.classification().labels().stream().map(LabelChip::labelId).toList();
    }

    @Test
    void aRenameIsRecordedWithItsAuthorAndUndoneByAnotherMember() {
        Document document = f.document("Scan 0815");
        commands.rename(f.actor, document.id(), "Stromvertrag 2026", document.version());

        DocumentChangeView rename = latest(document);
        assertThat(rename.action()).isEqualTo("RENAMED");
        assertThat(rename.actorKind()).isEqualTo("MEMBER");
        assertThat(rename.actorMember()).isEqualTo(f.lisa);
        assertThat(rename.changes()).containsExactly(new FieldChange("title", "Scan 0815", "Stromvertrag 2026"));
        assertThat(rename.undoable()).isTrue();
        assertThat(page(document).recordingSince()).isEqualTo(InMemoryDocumentHistoryRepository.RECORDING_SINCE);

        DocumentSummary undone = history.undo(asCedric, document.id(), rename.id());

        assertThat(undone.title()).isEqualTo("Scan 0815");
        assertThat(undone.titleOrigin()).isEqualTo("MANUAL");
        DocumentChangeView undo = latest(document);
        assertThat(undo.action()).isEqualTo("UNDONE");
        assertThat(undo.undoOf()).isEqualTo(rename.id());
        assertThat(undo.actorMember()).isEqualTo(f.cedric);
        assertThat(page(document).changes().get(1).undoneBy()).isEqualTo(undo.id());
        assertThatThrownBy(() -> history.undo(f.actor, document.id(), rename.id()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.alreadyUndone");
        assertThatThrownBy(() -> history.undo(f.actor, document.id(), undo.id()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.notSupported");
    }

    @Test
    void undoingALabelChangeKeepsLabelsAddedLaterAndRefusesWhenItsOwnLabelIsGoneAgain() {
        Document document = f.document("Telekom 10-2026");
        LabelView telekom = f.label("ORGANIZATION", "Telekom");
        LabelView internet = f.label("TOPIC", "Internet");
        LabelView invoice = f.label("DOCUMENT_TYPE", "Rechnung");

        labels(document, telekom);
        DocumentChangeView addTelekom = latest(document);
        assertThat(addTelekom.changes()).containsExactly(new FieldChange("labels", null, telekom.id().toString()));
        labels(document, telekom, internet);

        DocumentSummary undone = history.undo(asCedric, document.id(), addTelekom.id());
        assertThat(labelIds(undone)).containsExactly(internet.id());

        labels(document, internet, invoice);
        DocumentChangeView addInvoice = latest(document);
        labels(document, internet);
        assertThat(page(document).changes().get(1).undoable()).isFalse();
        assertThatThrownBy(() -> history.undo(asCedric, document.id(), addInvoice.id()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.changedSince")
                .satisfies(e -> assertThat(((UndoConflictException) e).details()).containsEntry("fields", "labels"));
    }

    @Test
    void undoingARemovalBringsTheLabelBackUnlessItWasDeletedMeanwhile() {
        Document document = f.document("Brief");
        LabelView bank = f.label("ORGANIZATION", "Sparkasse");
        LabelView tax = f.label("TOPIC", "Steuer");
        labels(document, bank, tax);
        labels(document, bank);
        DocumentChangeView removeTax = latest(document);

        assertThat(labelIds(history.undo(f.actor, document.id(), removeTax.id())))
                .containsExactlyInAnyOrder(bank.id(), tax.id());

        labels(document, bank);
        DocumentChangeView removeAgain = latest(document);
        f.labelService.delete(f.actor, tax.id(), f.labelVersion(tax.id()));
        assertThatThrownBy(() -> history.undo(f.actor, document.id(), removeAgain.id()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.labelGone");
    }

    @Test
    void undoingAMoveReturnsToTheEarlierFolderOnlyWhileItExists() {
        Document document = f.document("Police");
        CategoryView house = f.folder("Haus", null);
        CategoryView insurance = f.folder("Versicherungen", null);
        f.classificationService.place(f.actor, document.id(), house.id(), 0);
        DocumentChangeView intoHouse = latest(document);
        assertThat(intoHouse.changes()).containsExactly(new FieldChange("folder", null, house.id().toString()));

        DocumentSummary unsorted = history.undo(asCedric, document.id(), intoHouse.id());
        assertThat(unsorted.classification().categoryId()).isNull();

        f.classificationService.place(f.actor, document.id(), house.id(), f.classificationVersion(document.id()));
        f.classificationService.place(f.actor, document.id(), insurance.id(),
                f.classificationVersion(document.id()));
        DocumentChangeView intoInsurance = latest(document);
        f.categoryService.delete(f.actor, house.id(), f.categoryVersion(house.id()));

        assertThatThrownBy(() -> history.undo(asCedric, document.id(), intoInsurance.id()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.folderGone");
    }

    @Test
    void theTrashIsRecordedAndATrashedDocumentIsRestoredBeforeAnUndo() {
        Document document = f.document("Doppelt");
        DocumentSummary renamed = commands.rename(f.actor, document.id(), "Doppelt hochgeladen", document.version());
        DocumentChangeView rename = latest(document);
        DocumentSummary trashed = commands.moveToTrash(f.actor, document.id(), renamed.version());

        DocumentHistoryPage page = page(document);
        assertThat(page.changes()).extracting(DocumentChangeView::action).containsExactly("TRASHED", "RENAMED");
        assertThat(page.changes()).noneMatch(DocumentChangeView::undoable);
        assertThatThrownBy(() -> history.undo(f.actor, document.id(), rename.id()))
                .isInstanceOf(TrashedException.class);

        commands.restoreFromTrash(f.actor, document.id(), trashed.version());
        assertThat(latest(document).action()).isEqualTo("RESTORED");
        assertThat(page(document).changes().get(2).undoable()).isTrue();
    }

    @Test
    void aChangeOfAnotherDocumentIsNotFoundAndOnlyMembersReadHistory() {
        Document one = f.document("Eins");
        Document two = f.document("Zwei");
        commands.rename(f.actor, one.id(), "Eins!", one.version());
        UUID rename = latest(one).id();

        assertThatThrownBy(() -> history.undo(f.actor, two.id(), rename)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> history.history(new Actor(MemberId.random()), one.id(), null, 10))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> history.history(f.actor, one.id(), null, 101))
                .hasFieldOrPropertyWithValue("code", "validation.failed");
    }

    @Test
    void historyIsPagedNewestFirst() {
        Document document = f.document("Seiten");
        long version = document.version();
        for (int i = 1; i <= 3; i++) {
            version = commands.rename(f.actor, document.id(), "Seiten " + i, version).version();
        }

        DocumentHistoryPage first = history.history(f.actor, document.id(), null, 2);
        DocumentHistoryPage rest = history.history(f.actor, document.id(), first.nextBeforeSequence(), 2);

        assertThat(first.changes()).extracting(c -> c.changes().getFirst().after())
                .containsExactly("Seiten 3", "Seiten 2");
        assertThat(rest.changes()).extracting(c -> c.changes().getFirst().after()).containsExactly("Seiten 1");
        assertThat(rest.nextBeforeSequence()).isNull();
    }
}
