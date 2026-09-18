package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.domain.Document;
import dev.aegis.shared.InvalidInputException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LabelServiceTest {
    private final CatalogFixture fixture = new CatalogFixture();

    @Test
    void equalNamesResolveToTheExistingLabelWhileSimilarNamesStayDistinct() {
        LabelView telekom = fixture.label("ORGANIZATION", "Telekom");

        assertThatThrownBy(() -> fixture.label("ORGANIZATION", " telekom "))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.code()).isEqualTo("label.exists");
                    assertThat(e.details()).containsEntry("existingId", telekom.id().toString());
                });
        LabelView longer = fixture.label("ORGANIZATION", "Telekom Deutschland GmbH");
        assertThat(longer.id()).isNotEqualTo(telekom.id());
        assertThatThrownBy(() -> fixture.label("TAG", "Telekom"))
                .hasFieldOrPropertyWithValue("code", "label.existsInOtherFacet");
        assertThat(fixture.catalogQueries.resolveLabel(fixture.actor, "ORGANIZATION", "TELEKOM")).isPresent();
    }

    @Test
    void aliasesMakeAnEntityFindableUnderSeveralSpellings() {
        LabelView telekom = fixture.label("ORGANIZATION", "Telekom");

        fixture.labelService.replaceAliases(fixture.actor, telekom.id(),
                List.of("Deutsche Telekom", "Telekom Deutschland GmbH"), fixture.labelVersion(telekom.id()));

        assertThat(fixture.catalogQueries.resolveLabel(fixture.actor, "ORGANIZATION", "deutsche telekom"))
                .get().extracting(LabelView::id).isEqualTo(telekom.id());
        assertThat(fixture.catalogQueries.labels(fixture.actor)).filteredOn(l -> l.id().equals(telekom.id()))
                .singleElement().extracting(LabelView::aliases).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(String.class)).hasSize(2);
    }

    @Test
    void mergingMovesAssignmentsAndFolderMappingsAndKeepsDocuments() {
        LabelView telekom = fixture.label("ORGANIZATION", "Telekom");
        LabelView longName = fixture.label("ORGANIZATION", "Telekom Deutschland GmbH");
        CategoryView folder = fixture.folder("Telekom", null);
        fixture.categoryService.changeImplications(fixture.actor, folder.id(), List.of(longName.id()), List.of(),
                fixture.categoryVersion(folder.id()));
        Document document = fixture.document("Rechnung 09-2026");
        fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(longName.id()), 0);

        var result = fixture.labelService.merge(fixture.actor, longName.id(), telekom.id(), fixture.labelVersion(longName.id()));

        assertThat(result.documentsUpdated()).isEqualTo(1);
        assertThat(result.foldersUpdated()).isEqualTo(1);
        assertThat(result.target().aliases()).contains("Telekom Deutschland GmbH");
        assertThat(result.target().assignmentCount()).isEqualTo(1);
        assertThat(fixture.documents.findAll()).hasSize(1);
        var summary = fixture.classificationService.place(fixture.actor, document.id(), folder.id(),
                fixture.classificationVersion(document.id()));
        assertThat(summary.classification().labels()).extracting(LabelChip::labelId).containsExactly(telekom.id());
        assertThat(fixture.catalogQueries.labels(fixture.actor)).filteredOn(l -> l.id().equals(longName.id()))
                .singleElement().satisfies(redirect -> {
                    assertThat(redirect.status()).isEqualTo("MERGED");
                    assertThat(redirect.mergedInto()).isEqualTo(telekom.id());
                });
    }

    @Test
    void retiredLabelsKeepExistingAssignmentsButAcceptNoNewOnes() {
        LabelView topic = fixture.label("TOPIC", "Internet");
        Document document = fixture.document("Telekom");
        fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(topic.id()), 0);

        fixture.labelService.retire(fixture.actor, topic.id(), fixture.labelVersion(topic.id()));

        Document other = fixture.document("Vodafone");
        assertThatThrownBy(() -> fixture.classificationService.setLabels(fixture.actor, other.id(),
                List.of(topic.id()), 0)).hasFieldOrPropertyWithValue("code", "label.retired");
        var summary = fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(topic.id()),
                fixture.classificationVersion(document.id()));
        assertThat(summary.classification().labels()).extracting(LabelChip::labelId).containsExactly(topic.id());

        fixture.labelService.reactivate(fixture.actor, topic.id(), fixture.labelVersion(topic.id()));
        assertThat(fixture.classificationService.setLabels(fixture.actor, other.id(), List.of(topic.id()), 0)
                .classification().labels()).hasSize(1);
    }

    @Test
    void labelsAreDeletedOnlyWhenNothingReferencesThem() {
        LabelView tag = fixture.label("TAG", "Glasfaser");
        Document document = fixture.document("Vertrag");
        fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(tag.id()), 0);

        assertThatThrownBy(() -> fixture.labelService.delete(fixture.actor, tag.id(),
                fixture.labelVersion(tag.id()))).hasFieldOrPropertyWithValue("code", "label.inUse");

        fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(),
                fixture.classificationVersion(document.id()));
        fixture.labelService.delete(fixture.actor, tag.id(), fixture.labelVersion(tag.id()));

        assertThat(fixture.catalogQueries.labels(fixture.actor)).isEmpty();
        assertThat(fixture.documents.findById(document.id())).isPresent();
    }

    @Test
    void tagsCanBecomeTopicsWithoutLosingAssignments() {
        LabelView tag = fixture.label("TAG", "Glasfaser");
        Document document = fixture.document("Vertrag");
        fixture.classificationService.setLabels(fixture.actor, document.id(), List.of(tag.id()), 0);

        LabelView converted = fixture.labelService.convert(fixture.actor, tag.id(), "TOPIC", fixture.labelVersion(tag.id()));

        assertThat(converted.facet()).isEqualTo("TOPIC");
        assertThat(converted.assignmentCount()).isEqualTo(1);
        var summary = fixture.classificationService.place(fixture.actor, document.id(), null,
                fixture.classificationVersion(document.id()));
        assertThat(summary.classification().labels()).singleElement().extracting(LabelChip::facet)
                .isEqualTo("TOPIC");
    }

    @Test
    void labelCreationIsIdempotentForARetriedClientId() {
        UUID id = UUID.randomUUID();
        LabelView first = fixture.labelService.create(fixture.actor, id, "TOPIC", "Wasser");

        LabelView retried = fixture.labelService.create(fixture.actor, id, "TOPIC", "wasser");

        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(fixture.catalogQueries.labels(fixture.actor)).hasSize(1);
        assertThatThrownBy(() -> fixture.labelService.create(fixture.actor, id, "TOPIC", "Strom"))
                .hasFieldOrPropertyWithValue("code", "label.id.taken");
    }

    @Test
    void folderMappingsAreCountedForTheLabel() {
        LabelView topic = fixture.label("TOPIC", "Wasser");
        CategoryView folder = fixture.folder("Wasser", null);
        fixture.categoryService.changeImplications(fixture.actor, folder.id(), List.of(topic.id()),
                List.of(new SubjectView("HOUSE", null)),
                fixture.categoryVersion(folder.id()));

        assertThat(fixture.catalogQueries.labels(fixture.actor)).singleElement()
                .extracting(LabelView::folderCount).isEqualTo(1L);
    }
}
