package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.HouseholdDataWipe;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryCommands;
import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.ClassificationCommands;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.api.UploadDocument.UploadCommand;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.application.api.TaskCommands.CreateTaskCommand;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.portability.application.api.CreateExport;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.api.RestoreExport;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Export, wipe, restore: documents come back under their stable ids with new external archive ids, tasks keep
 * their source links, originals match, and a repeated restore changes nothing.
 */
class RestoreIT extends PostgresIT {
    @Autowired
    UploadDocument upload;
    @Autowired
    DocumentQueries documentQueries;
    @Autowired
    CategoryCommands categories;
    @Autowired
    LabelCommands labels;
    @Autowired
    ClassificationCommands classification;
    @Autowired
    CatalogQueries catalog;
    @Autowired
    TaskCommands tasks;
    @Autowired
    TaskQueries taskQueries;
    @Autowired
    CreateExport createExport;
    @Autowired
    RestoreExport restore;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FakePaperlessArchive archive;
    @Autowired
    dev.aegis.documents.application.api.DocumentAnalysisCommands analysisCommands;

    @Test
    void restoreIntoWipedInstancePreservesIdsAndSourceLinksDespiteNewExternalIds() throws Exception {
        Actor anna = new Actor(members.findCredentials(ANNA).orElseThrow().id());
        Actor ben = new Actor(members.findCredentials(BEN).orElseThrow().id());
        // Unique content: the shared fixture may already exist from another IT class in the same database.
        byte[] fixture = fixtureBytes();
        byte[] marker = (" %% restore drill " + System.nanoTime() + " %% ")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] pdf = new byte[fixture.length + marker.length];
        System.arraycopy(fixture, 0, pdf, 0, fixture.length);
        System.arraycopy(marker, 0, pdf, fixture.length, marker.length);
        var uploaded = upload.upload(anna, new UploadCommand("Restore Vertrag.pdf", "application/pdf", pdf.length,
                new ByteArrayInputStream(pdf), List.of(new SubjectView("MEMBER", ben.memberId())))).document();
        DocumentId documentId = uploaded.id();
        // A photographed letter travels through export and restore as the image it is (issue #80).
        byte[] photo = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 1, 2, (byte) 0xFF,
                (byte) 0xFE, 0, 10, 'r', 'e', 's', 't', 'o', 'r', 'e', (byte) (System.nanoTime() & 0x7F),
                (byte) 0xFF, (byte) 0xD9};
        DocumentId photoId = upload.upload(anna, new UploadCommand("Brief vom Amt.jpg", "image/jpeg", photo.length,
                new ByteArrayInputStream(photo), List.of(new SubjectView("HOUSE", null)))).document().id();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                documentQueries.get(anna, documentId).processing().state()).isEqualTo("ARCHIVED"));
        int originalPaperlessId = documentQueries.get(anna, documentId).processing().paperlessDocumentId();
        TaskSummary task = tasks.create(ben, new CreateTaskCommand("Nach Restore prüfen", "", anna.memberId(),
                LocalDate.of(2027, 1, 31), null, documentId));

        // A folder with an implied label plus an own organization label: the export must carry both.
        LabelView invoice = catalog.labels(anna).stream().filter(l -> "invoice".equals(l.builtinKey())).findFirst()
                .orElseThrow();
        LabelView provider = labels.create(anna, null, "ORGANIZATION", "Restore Provider " + System.nanoTime());
        CategoryView folder = categories.create(anna, new CreateCategory(null, null,
                "Restore Ordner " + System.nanoTime()));
        categories.changeImplications(anna, folder.id(), List.of(invoice.id()),
                List.of(new SubjectView("HOUSE", null)), folder.version());
        var filed = classification.place(anna, documentId, folder.id(),
                documentQueries.get(anna, documentId).classification().version());
        classification.setLabels(anna, documentId, List.of(provider.id()), filed.classification().version());
        // A member-chosen friendly filename is part of the analysis metadata and must survive the round trip.
        var named = analysisCommands.setFilename(anna, documentId, "Restore Vertrag Haus",
                documentQueries.get(anna, documentId).analysis().version());
        String friendlyName = named.filename().filename();
        assertThat(friendlyName).startsWith("Restore-Vertrag-Haus_");

        ExportSummary export = createExport.export(anna);
        assertThat(export.documentCount()).isGreaterThanOrEqualTo(1);
        assertThat(Files.isRegularFile(Path.of(export.location()).resolve("manifest.json"))).isTrue();

        // Wipe household data (members stay, like a fresh instance provisioned with the same usernames).
        HouseholdDataWipe.truncate(jdbc);
        try (var files = Files.list(DATA.resolve("ingress"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }
        archive.clear();
        assertThat(documentQueries.search(anna, "").items()).isEmpty();

        RestoreExport.RestoreReport report = restore.restore(anna, export.location());

        assertThat(report.documentsImported()).isEqualTo(export.documentCount());
        assertThat(report.tasksImported()).isEqualTo(export.taskCount());
        assertThat(report.memberIdMapping()).containsEntry(anna.memberId().toString(), anna.memberId().toString());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                documentQueries.get(anna, documentId).processing().state()).isEqualTo("ARCHIVED"));
        var restored = documentQueries.get(ben, documentId);
        assertThat(restored.processing().paperlessDocumentId()).as("new external id").isNotEqualTo(originalPaperlessId);
        // Whether the archive recognized text is derived data: not exported, measured again from the new archive.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                documentQueries.get(ben, documentId).textRecognition().recognizedCharacters()).isNotNull());
        assertThat(restored.subjects()).extracting(SubjectView::memberId).containsExactly(ben.memberId());
        assertThat(documentQueries.original(ben, documentId).bytes()).isEqualTo(pdf);
        assertThat(documentQueries.original(ben, documentId).filename()).isEqualTo(friendlyName);
        assertThat(restored.filename().source()).isEqualTo("MANUAL");
        assertThat(restored.titleOrigin()).isEqualTo("FILENAME");
        assertThat(restored.originalFilename()).isEqualTo("Restore Vertrag.pdf");
        assertThat(restored.analysis().state()).as("a restore never starts a model run")
                .isEqualTo("NOT_REQUESTED");
        assertThat(report.analysesApplied()).isGreaterThanOrEqualTo(1);
        assertThat(Files.readAllBytes(Path.of(export.location()).resolve("documents/originals/" + friendlyName)))
                .isEqualTo(pdf);
        var restoredPhoto = documentQueries.get(anna, photoId);
        assertThat(restoredPhoto.contentType()).isEqualTo("image/jpeg");
        assertThat(restoredPhoto.originalFilename()).isEqualTo("Brief vom Amt.jpg");
        var photoOriginal = documentQueries.original(anna, photoId);
        assertThat(photoOriginal.bytes()).isEqualTo(photo);
        assertThat(photoOriginal.contentType()).isEqualTo("image/jpeg");
        assertThat(photoOriginal.filename()).endsWith(".jpg");
        String photoStableId = photoId.toString().substring(0, 8);
        try (var exported = Files.list(Path.of(export.location()).resolve("documents/originals"))) {
            assertThat(exported.filter(file -> file.getFileName().toString().contains(photoStableId)))
                    .singleElement().satisfies(file -> {
                        assertThat(file.getFileName().toString()).endsWith(".jpg");
                        assertThat(Files.readAllBytes(file)).isEqualTo(photo);
                    });
        }
        TaskSummary restoredTask = taskQueries.get(ben, task.id());
        assertThat(restoredTask.source().documentId()).isEqualTo(documentId);
        assertThat(restoredTask.source().documentTitle()).isEqualTo("Restore Vertrag");
        assertThat(restoredTask.ownerId()).isEqualTo(anna.memberId());
        assertThat(restoredTask.dueDate()).isEqualTo(LocalDate.of(2027, 1, 31));

        // Folder, vocabulary and classification came back with their stable ids and their provenance.
        assertThat(report.foldersCreated()).isGreaterThanOrEqualTo(1);
        assertThat(report.classificationsApplied()).isGreaterThanOrEqualTo(1);
        assertThat(catalog.categories(anna).categories()).extracting(CategoryView::id).contains(folder.id());
        assertThat(catalog.categories(anna).categories()).filteredOn(view -> view.id().equals(folder.id()))
                .singleElement().satisfies(view -> {
                    assertThat(view.impliedLabelIds()).containsExactly(invoice.id());
                    assertThat(view.impliedSubjects()).extracting(SubjectView::kind).containsExactly("HOUSE");
                });
        assertThat(catalog.labels(anna)).extracting(LabelView::id).contains(provider.id());
        var restoredClassification = documentQueries.get(ben, documentId).classification();
        assertThat(restoredClassification.categoryId()).isEqualTo(folder.id());
        assertThat(restoredClassification.labels()).anySatisfy(chip -> {
            assertThat(chip.labelId()).isEqualTo(provider.id());
            assertThat(chip.source()).isEqualTo(LabelChip.SOURCE_OWN);
        }).anySatisfy(chip -> {
            assertThat(chip.labelId()).isEqualTo(invoice.id());
            assertThat(chip.source()).isEqualTo(LabelChip.SOURCE_FOLDER);
        });

        int foldersBefore = catalog.categories(anna).categories().size();
        int labelsBefore = catalog.labels(anna).size();
        RestoreExport.RestoreReport again = restore.restore(anna, export.location());
        assertThat(again.documentsImported()).isZero();
        assertThat(again.tasksImported()).isZero();
        assertThat(again.documentsAlreadyPresent()).isEqualTo(export.documentCount());
        assertThat(again.foldersCreated()).isZero();
        assertThat(again.labelsCreated()).isZero();
        assertThat(again.classificationsKept()).isGreaterThanOrEqualTo(1);
        assertThat(catalog.categories(anna).categories()).hasSize(foldersBefore);
        assertThat(catalog.labels(anna)).hasSize(labelsBefore);
        archive.available = false;
        try {
            assertThat(documentQueries.original(ben, documentId).bytes()).isEqualTo(pdf);
        } finally {
            archive.available = true;
        }
    }
}
