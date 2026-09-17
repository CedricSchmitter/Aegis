package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.api.DocumentQueries;
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
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                documentQueries.get(anna, documentId).processing().state()).isEqualTo("ARCHIVED"));
        int originalPaperlessId = documentQueries.get(anna, documentId).processing().paperlessDocumentId();
        TaskSummary task = tasks.create(ben, new CreateTaskCommand("Nach Restore prüfen", "", anna.memberId(),
                LocalDate.of(2027, 1, 31), null, documentId));

        ExportSummary export = createExport.export(anna);
        assertThat(export.documentCount()).isGreaterThanOrEqualTo(1);
        assertThat(Files.isRegularFile(Path.of(export.location()).resolve("manifest.json"))).isTrue();

        // Wipe household data (members stay, like a fresh instance provisioned with the same usernames).
        jdbc.execute("TRUNCATE TABLE scheduled_tasks, notices, tasks, document_labels, document_classifications, "
                + "document_subjects, documents");
        jdbc.execute("DELETE FROM category_label_effects");
        jdbc.execute("DELETE FROM category_subject_effects");
        jdbc.execute("DELETE FROM category_implied_labels");
        jdbc.execute("DELETE FROM category_implied_subjects");
        jdbc.execute("DELETE FROM categories");
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
        assertThat(restored.subjects()).extracting(SubjectView::memberId).containsExactly(ben.memberId());
        assertThat(documentQueries.original(ben, documentId).bytes()).isEqualTo(pdf);
        TaskSummary restoredTask = taskQueries.get(ben, task.id());
        assertThat(restoredTask.source().documentId()).isEqualTo(documentId);
        assertThat(restoredTask.source().documentTitle()).isEqualTo("Restore Vertrag");
        assertThat(restoredTask.ownerId()).isEqualTo(anna.memberId());
        assertThat(restoredTask.dueDate()).isEqualTo(LocalDate.of(2027, 1, 31));

        RestoreExport.RestoreReport again = restore.restore(anna, export.location());
        assertThat(again.documentsImported()).isZero();
        assertThat(again.tasksImported()).isZero();
        assertThat(again.documentsAlreadyPresent()).isEqualTo(export.documentCount());
        archive.available = false;
        try {
            assertThat(documentQueries.original(ben, documentId).bytes()).isEqualTo(pdf);
        } finally {
            archive.available = true;
        }
    }
}
