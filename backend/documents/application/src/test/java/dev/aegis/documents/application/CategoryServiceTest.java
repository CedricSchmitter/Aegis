package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.domain.Document;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryServiceTest {
    private final CatalogFixture fixture = new CatalogFixture();

    @Test
    void foldersAreCreatedWithStableIdsAndRetriesDoNotDuplicateThem() {
        UUID id = UUID.randomUUID();
        CategoryView house = fixture.categoryService.create(fixture.actor, new CreateCategory(id, null, "Haus"));

        CategoryView retried = fixture.categoryService.create(fixture.actor, new CreateCategory(id, null, " haus "));

        assertThat(retried.id()).isEqualTo(house.id()).isEqualTo(id);
        assertThat(fixture.catalogQueries.categories(fixture.actor).categories()).hasSize(1);
        assertThatThrownBy(() -> fixture.categoryService.create(fixture.actor,
                new CreateCategory(null, null, "HAUS"))).hasFieldOrPropertyWithValue("code",
                "category.name.duplicate");
        assertThatThrownBy(() -> fixture.categoryService.create(fixture.actor,
                new CreateCategory(UUID.randomUUID(), null, "Haus"))).hasFieldOrPropertyWithValue("code",
                "category.name.duplicate");
    }

    @Test
    void theListingIsDepthFirstAndCountsDocumentsPerFolderAndUnsorted() {
        CategoryView house = fixture.folder("Haus", null);
        CategoryView invoices = fixture.folder("Rechnungen", house.id());
        fixture.folder("Versicherungen", house.id());
        Document filed = fixture.document("Telekom Rechnung");
        fixture.document("Unsortiertes Schreiben");
        fixture.classificationService.place(fixture.actor, filed.id(), invoices.id(), 0);

        var listing = fixture.catalogQueries.categories(fixture.actor);

        assertThat(listing.categories()).extracting(CategoryView::name).containsExactly("Haus", "Rechnungen",
                "Versicherungen");
        assertThat(listing.categories()).filteredOn(c -> c.id().equals(invoices.id())).singleElement()
                .extracting(CategoryView::documentCount).isEqualTo(1L);
        assertThat(listing.unsortedCount()).isEqualTo(1);
        assertThat(listing.totalDocuments()).isEqualTo(2);
    }

    @Test
    void movingAFolderUpdatesInheritedLabelsWithoutTouchingOwnOnes() {
        LabelView invoice = fixture.label("DOCUMENT_TYPE", "Rechnung");
        LabelView internet = fixture.label("TOPIC", "Internet");
        LabelView telekom = fixture.label("ORGANIZATION", "Telekom");
        CategoryView house = fixture.folder("Haus", null);
        CategoryView invoices = fixture.folder("Rechnungen", house.id());
        CategoryView internetFolder = fixture.folder("Internet", invoices.id());
        fixture.categoryService.changeImplications(fixture.actor, house.id(), List.of(),
                List.of(new SubjectView("HOUSE", null)),
                fixture.categoryVersion(house.id()));
        fixture.categoryService.changeImplications(fixture.actor, invoices.id(), List.of(invoice.id()), List.of(),
                fixture.categoryVersion(invoices.id()));
        fixture.categoryService.changeImplications(fixture.actor, internetFolder.id(), List.of(internet.id()),
                List.of(),
                fixture.categoryVersion(internetFolder.id()));
        Document document = fixture.document("Telekom 09-2026");
        fixture.classificationService.place(fixture.actor, document.id(), internetFolder.id(), 0);
        fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(telekom.id()),
                fixture.classificationVersion(document.id()));

        var before = fixture.classificationService.place(fixture.actor, document.id(), internetFolder.id(),
                fixture.classificationVersion(document.id()));
        assertThat(before.classification().labels()).extracting(LabelChip::labelId)
                .containsExactlyInAnyOrder(invoice.id(), telekom.id(), internet.id());
        assertThat(before.classification().folderSubjects()).extracting(SubjectView::kind).containsExactly("HOUSE");

        CategoryView elsewhere = fixture.folder("Auto", null);
        fixture.categoryService.move(fixture.actor, internetFolder.id(), elsewhere.id(),
                currentVersion(internetFolder.id()));

        var after = fixture.classificationService.place(fixture.actor, document.id(), internetFolder.id(),
                fixture.classificationVersion(document.id()));
        assertThat(after.classification().labels()).extracting(LabelChip::labelId)
                .containsExactlyInAnyOrder(telekom.id(), internet.id());
        assertThat(after.classification().folderSubjects()).isEmpty();
        assertThat(after.classification().labels()).filteredOn(chip -> chip.labelId().equals(telekom.id()))
                .singleElement().extracting(LabelChip::source).isEqualTo(LabelChip.SOURCE_OWN);
    }

    @Test
    void deletingAFolderMovesItsContentUpAndKeepsLabelsTheDocumentsWouldLose() {
        LabelView invoice = fixture.label("DOCUMENT_TYPE", "Rechnung");
        LabelView internet = fixture.label("TOPIC", "Internet");
        CategoryView house = fixture.folder("Haus", null);
        CategoryView invoices = fixture.folder("Rechnungen", house.id());
        CategoryView internetFolder = fixture.folder("Internet", invoices.id());
        fixture.categoryService.changeImplications(fixture.actor, invoices.id(), List.of(invoice.id()),
                List.of(new SubjectView("HOUSE", null)),
                fixture.categoryVersion(invoices.id()));
        fixture.categoryService.changeImplications(fixture.actor, internetFolder.id(), List.of(internet.id()),
                List.of(),
                fixture.categoryVersion(internetFolder.id()));
        Document inInvoices = fixture.document("Wasser 2026");
        Document inInternet = fixture.document("Telekom 09-2026");
        fixture.classificationService.place(fixture.actor, inInvoices.id(), invoices.id(), 0);
        fixture.classificationService.place(fixture.actor, inInternet.id(), internetFolder.id(), 0);

        var result = fixture.categoryService.delete(fixture.actor, invoices.id(), currentVersion(invoices.id()));

        assertThat(result.contentsMovedTo()).isEqualTo(house.id());
        assertThat(result.documentsMoved()).isEqualTo(1);
        assertThat(result.foldersMoved()).isEqualTo(1);
        assertThat(result.labelsKept()).isEqualTo(2);
        assertThat(result.subjectsKept()).isEqualTo(2);
        assertThat(fixture.documents.findAll()).hasSize(2);

        var movedUp = fixture.classificationService.place(fixture.actor, inInvoices.id(), house.id(),
                fixture.classificationVersion(inInvoices.id()));
        assertThat(movedUp.classification().labels()).extracting(LabelChip::labelId).containsExactly(invoice.id());
        assertThat(movedUp.classification().labels()).singleElement().extracting(LabelChip::source)
                .isEqualTo(LabelChip.SOURCE_OWN);
        assertThat(movedUp.subjects()).extracting(SubjectView::kind).contains("HOUSE");

        var stillInInternet = fixture.classificationService.place(fixture.actor, inInternet.id(),
                internetFolder.id(), fixture.classificationVersion(inInternet.id()));
        assertThat(stillInInternet.classification().path()).extracting(p -> p.name())
                .containsExactly("Haus", "Internet");
        assertThat(stillInInternet.classification().labels()).extracting(LabelChip::labelId)
                .containsExactlyInAnyOrder(invoice.id(), internet.id());
    }

    @Test
    void aDeletedTopLevelFolderLeavesItsDocumentsUnsorted() {
        CategoryView house = fixture.folder("Haus", null);
        Document document = fixture.document("Vertrag");
        fixture.classificationService.place(fixture.actor, document.id(), house.id(), 0);

        var result = fixture.categoryService.delete(fixture.actor, house.id(), currentVersion(house.id()));

        assertThat(result.contentsMovedTo()).isNull();
        assertThat(fixture.catalogQueries.categories(fixture.actor).unsortedCount()).isEqualTo(1);
        assertThat(fixture.documents.findById(document.id())).isPresent();
    }

    @Test
    void retiredLabelsCannotBeMappedAndUnknownFoldersAreReported() {
        LabelView topic = fixture.label("TOPIC", "Internet");
        CategoryView folder = fixture.folder("Haus", null);
        fixture.labelService.retire(fixture.actor, topic.id(), fixture.labelVersion(topic.id()));

        assertThatThrownBy(() -> fixture.categoryService.changeImplications(fixture.actor, folder.id(),
                List.of(topic.id()), List.of(), 0)).hasFieldOrPropertyWithValue("code", "label.retired");
        assertThatThrownBy(() -> fixture.categoryService.rename(fixture.actor, UUID.randomUUID(), "X", 0))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> fixture.categoryService.rename(fixture.actor, folder.id(), "Zuhause", 7))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void onlyHouseholdMembersChangeTheTree() {
        Actor stranger = new Actor(MemberId.random());

        assertThatThrownBy(() -> fixture.categoryService.create(stranger, new CreateCategory(null, null, "Haus")))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> fixture.catalogQueries.categories(stranger))
                .isInstanceOf(NotAuthorizedException.class);
    }

    private long currentVersion(UUID id) {
        return fixture.catalogQueries.categories(fixture.actor).categories().stream()
                .filter(c -> c.id().equals(id)).findFirst().orElseThrow().version();
    }
}
