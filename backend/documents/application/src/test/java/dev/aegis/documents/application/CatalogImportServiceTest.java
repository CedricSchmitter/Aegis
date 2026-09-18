package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.documents.application.api.ImportCatalog.AssignmentImport;
import dev.aegis.documents.application.api.ImportCatalog.ClassificationOutcome;
import dev.aegis.documents.application.api.ImportCatalog.FolderImport;
import dev.aegis.documents.application.api.ImportCatalog.LabelImport;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogImportServiceTest {
    private final CatalogFixture fixture = new CatalogFixture();

    @Test
    void untouchedBuiltinRestoresAliasesAndRetirementWithoutACustomName() {
        LabelId id = seedBuiltin();
        var imported = new LabelImport(id.value(), "DOCUMENT_TYPE", "invoice", null,
                List.of("Bill"), "RETIRED", null);

        var result = fixture.importService.importCatalog(fixture.actor, List.of(imported), List.of());

        Label restored = fixture.labels.findById(id).orElseThrow();
        assertThat(restored.name()).isEmpty();
        assertThat(restored.status()).isEqualTo(LabelStatus.RETIRED);
        assertThat(restored.aliases()).containsExactly("Bill");
        assertThat(result.warnings()).isEmpty();
        fixture.importService.importCatalog(fixture.actor, List.of(imported), List.of());
        assertThat(fixture.labels.findById(id).orElseThrow().version()).isEqualTo(restored.version());
    }

    @Test
    void untouchedBuiltinRestoresCustomNameAliasesAndStatusTogether() {
        LabelId id = seedBuiltin();

        fixture.importService.importCatalog(fixture.actor, List.of(new LabelImport(id.value(),
                "DOCUMENT_TYPE", "invoice", "Receipt", List.of("Bill"), "RETIRED", null)), List.of());

        Label restored = fixture.labels.findById(id).orElseThrow();
        assertThat(restored.name()).contains("Receipt");
        assertThat(restored.aliases()).containsExactly("Bill");
        assertThat(restored.status()).isEqualTo(LabelStatus.RETIRED);
    }

    @Test
    void locallyEditedBuiltinKeepsItsNameAliasesAndStatus() {
        LabelId id = seedBuiltin();
        fixture.labelService.replaceAliases(fixture.actor, id.value(), List.of("Local alias"), 0);
        long version = fixture.labelVersion(id.value());

        fixture.importService.importCatalog(fixture.actor, List.of(new LabelImport(id.value(),
                "DOCUMENT_TYPE", "invoice", "Receipt", List.of("Bill"), "RETIRED", null)), List.of());

        Label local = fixture.labels.findById(id).orElseThrow();
        assertThat(local.name()).isEmpty();
        assertThat(local.aliases()).containsExactly("Local alias");
        assertThat(local.status()).isEqualTo(LabelStatus.ACTIVE);
        assertThat(local.version()).isEqualTo(version);
    }

    @Test
    void conflictingBuiltinDoesNotPolluteNameMatchingForLaterImports() {
        LabelId id = seedBuiltin();
        LabelView local = fixture.label("DOCUMENT_TYPE", "Receipt");
        UUID other = UUID.randomUUID();

        var result = fixture.importService.importCatalog(fixture.actor, List.of(
                new LabelImport(id.value(), "DOCUMENT_TYPE", "invoice", "Receipt", List.of(), "RETIRED", null),
                new LabelImport(other, "DOCUMENT_TYPE", null, "Receipt", List.of(), "ACTIVE", null)), List.of());

        assertThat(result.warnings()).hasSize(2);
        assertThat(result.labelIds()).containsEntry(other, local.id());
        Label retained = fixture.labels.findById(id).orElseThrow();
        assertThat(retained.name()).isEmpty();
        assertThat(retained.aliases()).containsExactly("Invoice", "Rechnung");
        assertThat(retained.status()).isEqualTo(LabelStatus.ACTIVE);
        assertThat(retained.version()).isZero();
    }

    private LabelId seedBuiltin() {
        LabelId id = LabelId.random();
        fixture.labels.seed(Label.rehydrate(id, Facet.DOCUMENT_TYPE, "invoice", null,
                List.of("Invoice", "Rechnung"), LabelStatus.ACTIVE, null, null,
                CatalogFixture.NOW, CatalogFixture.NOW, 0));
        return id;
    }

    @Test
    void restoreCreatesMissingEntriesKeepsLocalOnesAndIsIdempotent() {
        UUID exportedTopic = UUID.randomUUID();
        UUID exportedFolder = UUID.randomUUID();
        UUID exportedChild = UUID.randomUUID();
        List<LabelImport> labels = List.of(new LabelImport(exportedTopic, "TOPIC", null, "Internet",
                List.of("DSL"), "ACTIVE", null));
        List<FolderImport> folders = List.of(
                new FolderImport(exportedFolder, null, "Haus", List.of(), List.of(new SubjectView("HOUSE", null))),
                new FolderImport(exportedChild, exportedFolder, "Internet", List.of(exportedTopic), List.of()));

        var first = fixture.importService.importCatalog(fixture.actor, labels, folders);

        assertThat(first.labelsCreated()).isEqualTo(1);
        assertThat(first.foldersCreated()).isEqualTo(2);
        assertThat(first.labelIds()).containsEntry(exportedTopic, exportedTopic);
        assertThat(first.folderIds()).containsEntry(exportedChild, exportedChild);
        assertThat(first.warnings()).isEmpty();

        var again = fixture.importService.importCatalog(fixture.actor, labels, folders);

        assertThat(again.labelsCreated()).isZero();
        assertThat(again.foldersCreated()).isZero();
        assertThat(fixture.catalogQueries.categories(fixture.actor).categories()).hasSize(2);
        assertThat(fixture.catalogQueries.labels(fixture.actor)).hasSize(1);
    }

    @Test
    void exportedEntriesWithTheSameNameMapToTheLocalOnesWithAWarning() {
        LabelView local = fixture.label("ORGANIZATION", "Telekom");
        var localFolder = fixture.folder("Haus", null);
        UUID exportedLabel = UUID.randomUUID();
        UUID exportedFolder = UUID.randomUUID();

        var result = fixture.importService.importCatalog(fixture.actor,
                List.of(new LabelImport(exportedLabel, "ORGANIZATION", null, "telekom", List.of(), "ACTIVE", null)),
                List.of(new FolderImport(exportedFolder, null, "HAUS", List.of(exportedLabel), List.of())));

        assertThat(result.labelIds()).containsEntry(exportedLabel, local.id());
        assertThat(result.folderIds()).containsEntry(exportedFolder, localFolder.id());
        assertThat(result.warnings()).hasSize(2);
        assertThat(fixture.catalogQueries.labels(fixture.actor)).hasSize(1);
    }

    @Test
    void classificationsAreAppliedOnlyToStillUnclassifiedDocuments() {
        LabelView telekom = fixture.label("ORGANIZATION", "Telekom");
        LabelView internet = fixture.label("TOPIC", "Internet");
        var folder = fixture.folder("Haus", null);
        Document imported = fixture.document("Telekom 09-2026");
        Document edited = fixture.document("Wasser 2026");
        fixture.classificationService.setLabels(fixture.actor, edited.id(), List.of(internet.id()), 0);

        var applied = fixture.importService.importClassification(fixture.actor, imported.id(), folder.id(),
                List.of(new AssignmentImport(telekom.id(), "AUTOMATIC", "APPLIED", 0.8, "run:7", null,
                        CatalogFixture.NOW)));
        var kept = fixture.importService.importClassification(fixture.actor, edited.id(), folder.id(),
                List.of(new AssignmentImport(telekom.id(), "MANUAL", "CONFIRMED", null, null, null,
                        CatalogFixture.NOW)));

        assertThat(applied).isEqualTo(ClassificationOutcome.APPLIED);
        assertThat(kept).isEqualTo(ClassificationOutcome.KEPT_EXISTING);
        var summary = fixture.classificationService.place(fixture.actor, imported.id(), folder.id(),
                fixture.classificationVersion(imported.id()));
        assertThat(summary.classification().labels()).singleElement().satisfies(chip -> {
            assertThat(chip.labelId()).isEqualTo(telekom.id());
            assertThat(chip.origin()).isEqualTo("AUTOMATIC");
            assertThat(chip.reviewState()).isEqualTo("APPLIED");
            assertThat(chip.confidence()).isEqualTo(0.8);
            assertThat(chip.sourceRef()).isEqualTo("run:7");
        });
        var editedSummary = fixture.classificationService.place(fixture.actor, edited.id(), null,
                fixture.classificationVersion(edited.id()));
        assertThat(editedSummary.classification().labels()).extracting(LabelChip::labelId)
                .containsExactly(internet.id());
    }

    @Test
    void unknownLabelsAndFoldersAreDroppedInsteadOfFailingTheRestore() {
        Document document = fixture.document("Scan");

        var outcome = fixture.importService.importClassification(fixture.actor, document.id(), UUID.randomUUID(),
                List.of(new AssignmentImport(UUID.randomUUID(), "MANUAL", "CONFIRMED", null, null, null, null)));

        assertThat(outcome).isEqualTo(ClassificationOutcome.NOTHING_TO_APPLY);
        assertThat(fixture.classificationService.place(fixture.actor, document.id(), null, 0).classification()
                .labels()).isEmpty();
    }
}
