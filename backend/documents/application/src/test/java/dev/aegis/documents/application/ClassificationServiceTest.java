package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.ClassificationCommands.Placement;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.api.ProposeClassification.LabelProposal;
import dev.aegis.documents.application.api.ProposeClassification.ProposalCommand;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.domain.Document;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.StaleVersionException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassificationServiceTest {
    private final CatalogFixture fixture = new CatalogFixture();

    @Test
    void theOwnerExampleShowsInvoiceTelekomInternetAndHouseWithoutMaintainingHouseTwice() {
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
        DocumentSummary summary = fixture.classificationService.setLabels(fixture.actor, document.id(),
                List.of(telekom.id()), fixture.classificationVersion(document.id()));

        assertThat(summary.classification().path()).extracting(p -> p.name())
                .containsExactly("Haus", "Rechnungen", "Internet");
        assertThat(summary.classification().labels()).extracting(LabelChip::labelId)
                .containsExactlyInAnyOrder(invoice.id(), telekom.id(), internet.id());
        assertThat(summary.classification().labels()).filteredOn(c -> c.labelId().equals(invoice.id()))
                .singleElement().satisfies(chip -> {
                    assertThat(chip.source()).isEqualTo(LabelChip.SOURCE_FOLDER);
                    assertThat(chip.folderId()).isEqualTo(invoices.id());
                });
        assertThat(summary.classification().folderSubjects()).extracting(SubjectView::kind).containsExactly("HOUSE");
    }

    @Test
    void anOwnDocumentTypeOverridesTheFolderType() {
        LabelView invoice = fixture.label("DOCUMENT_TYPE", "Rechnung");
        LabelView contract = fixture.label("DOCUMENT_TYPE", "Vertrag");
        CategoryView invoices = fixture.folder("Rechnungen", null);
        fixture.categoryService.changeImplications(fixture.actor, invoices.id(), List.of(invoice.id()), List.of(),
                fixture.categoryVersion(invoices.id()));
        Document document = fixture.document("Telekom Vertrag");
        fixture.classificationService.place(fixture.actor, document.id(), invoices.id(), 0);

        DocumentSummary summary = fixture.classificationService.setLabels(fixture.actor, document.id(),
                List.of(contract.id()), fixture.classificationVersion(document.id()));

        assertThat(summary.classification().labels()).extracting(LabelChip::labelId).containsExactly(contract.id());
        assertThatThrownBy(() -> fixture.classificationService.setLabels(fixture.actor, document.id(),
                List.of(contract.id(), invoice.id()), fixture.classificationVersion(document.id())))
                .hasFieldOrPropertyWithValue("code", "classification.singleType");
    }

    @Test
    void concurrentEditsConflictInsteadOfOverwritingTheOtherMember() {
        Document document = fixture.document("Vertrag");
        LabelView topic = fixture.label("TOPIC", "Internet");
        fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(topic.id()), 0);

        assertThatThrownBy(() -> fixture.classificationService.setLabels(new Actor(fixture.cedric), document.id(),
                List.of(), 0)).isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> fixture.classificationService.place(fixture.actor, document.id(), null, 0))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void severalDocumentsAreFiledInOneStepAndUnknownVersionsAbortTheMove() {
        CategoryView folder = fixture.folder("Haus", null);
        Document first = fixture.document("A");
        Document second = fixture.document("B");

        int moved = fixture.classificationService.placeAll(fixture.actor,
                List.of(new Placement(first.id(), 0), new Placement(second.id(), 0)), folder.id());

        assertThat(moved).isEqualTo(2);
        assertThat(fixture.catalogQueries.categories(fixture.actor).unsortedCount()).isZero();
        assertThatThrownBy(() -> fixture.classificationService.placeAll(fixture.actor,
                List.of(new Placement(first.id(), 0)), null)).isInstanceOf(StaleVersionException.class);
    }

    @Test
    void automaticProposalsBecomeVisibleSuggestionsThatMembersAcceptOrReject() {
        LabelView invoice = fixture.label("DOCUMENT_TYPE", "Rechnung");
        LabelView telekom = fixture.label("ORGANIZATION", "Telekom");
        Document document = fixture.document("Scan 0815");

        var outcome = fixture.classificationService.propose(fixture.actor, new ProposalCommand(document.id(), 0,
                "run:1", List.of(new LabelProposal(invoice.id(), 0.95, true),
                        new LabelProposal(telekom.id(), 0.42, false))));

        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(outcome.suggested()).isEqualTo(1);
        DocumentSummary withSuggestion = fixture.classificationService.place(fixture.actor, document.id(), null,
                outcome.version());
        assertThat(withSuggestion.classification().labels()).filteredOn(c -> c.labelId().equals(telekom.id()))
                .singleElement().satisfies(chip -> {
                    assertThat(chip.reviewState()).isEqualTo("SUGGESTED");
                    assertThat(chip.origin()).isEqualTo("AUTOMATIC");
                    assertThat(chip.confidence()).isEqualTo(0.42);
                });

        DocumentSummary accepted = fixture.classificationService.acceptSuggestion(fixture.actor, document.id(),
                telekom.id(), fixture.classificationVersion(document.id()));
        assertThat(accepted.classification().labels()).filteredOn(c -> c.labelId().equals(telekom.id()))
                .singleElement().extracting(LabelChip::reviewState).isEqualTo("CONFIRMED");

        DocumentSummary rejected = fixture.classificationService.rejectSuggestion(fixture.actor, document.id(),
                invoice.id(), fixture.classificationVersion(document.id()));
        assertThat(rejected.classification().labels()).extracting(LabelChip::labelId).containsExactly(telekom.id());
    }

    @Test
    void laterRunsRespectMemberDecisionsAndStaleAnalysesAreRefused() {
        LabelView telekom = fixture.label("ORGANIZATION", "Telekom");
        LabelView vodafone = fixture.label("ORGANIZATION", "Vodafone");
        Document document = fixture.document("Scan 0815");
        fixture.classificationService.propose(fixture.actor, new ProposalCommand(document.id(), 0, "run:1",
                List.of(new LabelProposal(vodafone.id(), 0.6, true))));
        fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(telekom.id()),
                fixture.classificationVersion(document.id()));

        long version = fixture.classificationVersion(document.id());
        var retry = fixture.classificationService.propose(fixture.actor, new ProposalCommand(document.id(), version,
                "run:2", List.of(new LabelProposal(vodafone.id(), 0.9, true))));

        assertThat(retry.applied()).isZero();
        assertThat(retry.skipped()).isEqualTo(1);
        DocumentSummary summary = fixture.classificationService.place(fixture.actor, document.id(), null,
                fixture.classificationVersion(document.id()));
        assertThat(summary.classification().labels()).extracting(LabelChip::labelId).containsExactly(telekom.id());
        assertThatThrownBy(() -> fixture.classificationService.propose(fixture.actor,
                new ProposalCommand(document.id(), version - 1, "run:3",
                        List.of(new LabelProposal(vodafone.id(), 0.9, true)))))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void unknownAndRetiredLabelsAreSkippedInProposalsAndRejectedForMembers() {
        LabelView topic = fixture.label("TOPIC", "Internet");
        fixture.labelService.retire(fixture.actor, topic.id(), fixture.labelVersion(topic.id()));
        Document document = fixture.document("Scan");

        var outcome = fixture.classificationService.propose(fixture.actor, new ProposalCommand(document.id(), 0,
                "run:1", List.of(new LabelProposal(topic.id(), 0.9, true),
                        new LabelProposal(java.util.UUID.randomUUID(), 0.9, true))));

        assertThat(outcome.applied()).isZero();
        assertThat(outcome.skipped()).isEqualTo(2);
    }

    @Test
    void onlyHouseholdMembersClassifyDocuments() {
        Document document = fixture.document("Vertrag");
        Actor stranger = new Actor(MemberId.random());

        assertThatThrownBy(() -> fixture.classificationService.place(stranger, document.id(), null, 0))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> fixture.classificationService.setLabels(stranger, document.id(), List.of(), 0))
                .isInstanceOf(NotAuthorizedException.class);
    }
}
