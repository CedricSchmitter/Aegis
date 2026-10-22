package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Trash and restore of documents (ADR-0010) through the application services. */
class DocumentTrashServiceTest {
    private CatalogFixture f;
    private final List<Document> analysisTriggers = new ArrayList<>();
    private DocumentCommandService commands;
    private DocumentQueryService queries;

    @BeforeEach
    void setUp() {
        f = new CatalogFixture();
        commands = new DocumentCommandService(f.documents, f.scheduler, f.presenter, analysisTriggers::add,
                f.persons, f.access, Trash.RecoveryWindow.DEFAULT, f.clock, f.recorder);
        queries = new DocumentQueryService(f.documents, new TestDoubles.FakeArchive(), new InMemoryOriginalStore(),
                f.presenter, f.catalog, f.categories, f.labels, f.classifications, f.views, f.placements,
                new InMemoryDocumentAnalysisRepository(), TestDoubles.analysisSettings(), f.access, f.clock);
    }

    private long version(Document document) {
        return f.documents.findById(document.id()).orElseThrow().version();
    }

    @Test
    void aTrashedDocumentLeavesSearchCountsAndFoldersButStaysReadableByLink() {
        UUID house = f.folder("Haus", null).id();
        Document kept = f.document("Stromrechnung");
        Document trashed = f.document("Falscher Scan");
        f.classificationService.place(f.actor, trashed.id(), house, 0);

        DocumentSummary result = commands.moveToTrash(f.actor, trashed.id(), version(trashed));

        assertThat(result.trash()).isEqualTo(new Trash(f.lisa, CatalogFixture.NOW,
                CatalogFixture.NOW.plus(Duration.ofDays(30))));
        assertThat(queries.search(f.actor, "").items()).extracting(DocumentSummary::id).containsExactly(kept.id());
        assertThat(queries.search(f.actor, "").totalDocuments()).isEqualTo(1);
        assertThat(queries.search(f.actor, "Scan").items()).isEmpty();
        assertThat(f.catalog.documentsPerCategory()).doesNotContainKey(house);
        assertThat(queries.trashed(f.actor)).extracting(DocumentSummary::id).containsExactly(trashed.id());
        assertThat(queries.get(f.actor, trashed.id()).trashed()).isTrue();
        assertThat(queries.get(f.actor, trashed.id()).classification().categoryId()).isEqualTo(house);
        assertThat(queries.findReference(trashed.id())).get()
                .satisfies(reference -> assertThat(reference.trashed()).isTrue());
    }

    @Test
    void restoringBringsBackTheSameDocumentWithItsFolder() {
        UUID house = f.folder("Haus", null).id();
        Document document = f.document("Mietvertrag");
        f.classificationService.place(f.actor, document.id(), house, 0);
        commands.moveToTrash(f.actor, document.id(), version(document));

        DocumentSummary restored = commands.restoreFromTrash(new Actor(f.cedric), document.id(), version(document));

        assertThat(restored.trash()).isNull();
        assertThat(restored.id()).isEqualTo(document.id());
        assertThat(restored.classification().categoryId()).isEqualTo(house);
        assertThat(queries.search(f.actor, "").items()).extracting(DocumentSummary::id).containsExactly(document.id());
        assertThat(queries.trashed(f.actor)).isEmpty();
        assertThat(f.catalog.documentsPerCategory()).containsEntry(house, 1L);
    }

    @Test
    void trashAndRestoreAreVersionCheckedButARepeatedRequestIsHarmless() {
        Document document = f.document("Rechnung");
        long before = version(document);

        assertThatThrownBy(() -> commands.moveToTrash(f.actor, document.id(), before + 1))
                .isInstanceOf(StaleVersionException.class);
        DocumentSummary trashed = commands.moveToTrash(f.actor, document.id(), version(document));
        // A retried request after a lost response carries the old version and must not fail or re-trash.
        DocumentSummary again = commands.moveToTrash(new Actor(f.cedric), document.id(), before);
        assertThat(again.trash()).isEqualTo(trashed.trash());

        assertThatThrownBy(() -> commands.restoreFromTrash(f.actor, document.id(), before + 42))
                .isInstanceOf(StaleVersionException.class);
        commands.restoreFromTrash(f.actor, document.id(), version(document));
        assertThat(commands.restoreFromTrash(f.actor, document.id(), before).trash()).isNull();
    }

    @Test
    void memberChangesWaitForTheRestore() {
        Document document = f.document("Kontoauszug");
        commands.moveToTrash(f.actor, document.id(), version(document));

        assertThatThrownBy(() -> commands.rename(f.actor, document.id(), "Neu", version(document)))
                .isInstanceOf(TrashedException.class).hasFieldOrPropertyWithValue("code", "document.trashed");
        assertThatThrownBy(() -> f.classificationService.setLabels(f.actor, document.id(), List.of(), 0))
                .isInstanceOf(TrashedException.class);
        assertThatThrownBy(() -> f.classificationService.place(f.actor, document.id(), null, 0))
                .isInstanceOf(TrashedException.class);
    }

    @Test
    void restoringAnArchivedDocumentStartsTheAnalysisThatWaitedWhileItWasTrashed() {
        Document received = f.document("Upload im Papierkorb");
        Document archived = f.document("Archiviert im Papierkorb");
        archived.markArchived(12, CatalogFixture.NOW);
        f.documents.save(archived);
        commands.moveToTrash(f.actor, received.id(), version(received));
        commands.moveToTrash(f.actor, archived.id(), version(archived));

        commands.restoreFromTrash(f.actor, received.id(), version(received));
        commands.restoreFromTrash(f.actor, archived.id(), version(archived));

        assertThat(analysisTriggers).extracting(Document::id).containsExactly(archived.id());
        assertThat(analysisTriggers.get(0).state()).isEqualTo(ProcessingState.ARCHIVED);
        assertThat(analysisTriggers.get(0).isTrashed()).isFalse();
    }

    @Test
    void deletingAFolderMovesItsTrashedDocumentsAlongSoARestoreNeverPointsAtAMissingFolder() {
        UUID house = f.folder("Haus", null).id();
        UUID insurance = f.folder("Versicherungen", house).id();
        Document document = f.document("Alte Police");
        f.classificationService.place(f.actor, document.id(), insurance, 0);
        commands.moveToTrash(f.actor, document.id(), version(document));

        f.categoryService.delete(f.actor, insurance, f.categoryVersion(insurance));
        DocumentSummary restored = commands.restoreFromTrash(f.actor, document.id(), version(document));

        assertThat(restored.classification().categoryId()).isEqualTo(house);
        assertThat(f.catalog.documentsPerCategory()).containsEntry(house, 1L);
    }

    @Test
    void onlyHouseholdMembersUseTheTrash() {
        Document document = f.document("Privat");
        Actor stranger = new Actor(MemberId.random());

        assertThatThrownBy(() -> commands.moveToTrash(stranger, document.id(), version(document)))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> queries.trashed(stranger)).isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void aLabelOnlyTrashedDocumentsStillUseCannotBeDeletedAndSaysWhy() {
        var label = f.label("TAG", "Steuer 2025");
        Document document = f.document("Steuerbescheid");
        f.classificationService.setLabels(f.actor, document.id(), List.of(label.id()), 0);
        commands.moveToTrash(f.actor, document.id(), version(document));

        assertThat(f.catalog.labelUsage()).doesNotContainKey(label.id());
        assertThatThrownBy(() -> f.labelService.delete(f.actor, label.id(), f.labelVersion(label.id())))
                .isInstanceOf(dev.aegis.shared.InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "label.inUseInTrash");
    }
}
