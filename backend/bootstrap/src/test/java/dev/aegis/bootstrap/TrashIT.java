package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.contracts.application.api.ContractCommands;
import dev.aegis.contracts.application.api.ContractCommands.CreateContractCommand;
import dev.aegis.contracts.application.api.ContractCommands.EnterFactCommand;
import dev.aegis.contracts.application.api.ContractDeadlineQuery;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.portability.application.api.CreateExport;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.api.RestoreExport;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.TaskId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Recoverable trash (ADR-0010) against PostgreSQL, the scheduler and the HTTP API: records keep their
 * identity and data, ordinary views hide them, commands are version-checked and the trash state survives export
 * and restore.
 */
class TrashIT extends PostgresIT {
    @Autowired ContractCommands contracts;
    @Autowired ContractQueries contractQueries;
    @Autowired ContractDeadlineQuery deadlines;
    @Autowired TaskRepository tasks;
    @Autowired CreateExport createExport;
    @Autowired RestoreExport restore;
    @Autowired MemberCredentialsQuery members;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @LocalServerPort int port;

    @Test
    void aDocumentAndItsTaskGoToTheTrashAndComeBackOverHttp() throws Exception {
        ApiClient anna = new ApiClient(port);
        assertThat(anna.login(ANNA, PASSWORD)).isTrue();
        ApiClient ben = new ApiClient(port);
        assertThat(ben.login(BEN, PASSWORD)).isTrue();
        byte[] pdf = uniquePdf("trash-document");
        ApiClient.Response uploaded = anna.upload("/api/documents", "Falscher Scan.pdf", pdf, List.of("HOUSE"));
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        String documentId = anna.json(uploaded).path("document").path("id").asText();
        var task = anna.postJson("/api/tasks", "{\"title\":\"Scan prüfen\",\"sourceDocumentId\":\"" + documentId
                + "\",\"dueDate\":\"" + LocalDate.now(clock) + "\"}");
        assertThat(task.status()).isEqualTo(201);
        String taskId = anna.json(task).path("id").asText();
        // Archival changes the version; trash the document once it is in the archive.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(anna.json(anna.get("/api/documents/"
                + documentId)).path("processing").path("state").asText()).isEqualTo("ARCHIVED"));

        long version = anna.json(anna.get("/api/documents/" + documentId)).path("version").asLong();
        var stale = anna.postJson("/api/documents/" + documentId + "/trash", "{\"expectedVersion\":" + (version + 5)
                + "}");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(anna.json(stale).path("code").asText()).isEqualTo("version.conflict");
        var trashed = anna.postJson("/api/documents/" + documentId + "/trash", "{\"expectedVersion\":" + version
                + "}");
        assertThat(trashed.status()).as(trashed.body()).isEqualTo(200);
        JsonNode trash = anna.json(trashed).path("trash");
        assertThat(trash.path("trashedAt").isMissingNode()).isFalse();
        assertThat(trash.path("restorableUntil").asText()).isNotBlank();

        // Hidden from the ordinary collection for both members, still readable by its link.
        assertThat(ben.get("/api/documents?size=100").body()).doesNotContain(documentId);
        assertThat(ben.get("/api/documents/trash").body()).contains(documentId);
        assertThat(ben.json(ben.get("/api/documents/" + documentId)).path("trash").isObject()).isTrue();
        assertThat(ben.json(ben.get("/api/tasks/" + taskId)).path("source").path("documentTrashed").asBoolean())
                .isTrue();
        var rename = ben.putJson("/api/documents/" + documentId + "/title", "{\"title\":\"Neu\",\"expectedVersion\":"
                + anna.json(trashed).path("version").asLong() + "}");
        assertThat(rename.status()).isEqualTo(409);
        assertThat(ben.json(rename).path("code").asText()).isEqualTo("document.trashed");
        assertThat(jdbc.queryForObject("SELECT trashed_at IS NOT NULL FROM documents WHERE id = ?", Boolean.class,
                UUID.fromString(documentId))).isTrue();

        // The same bytes uploaded again resolve to the trashed document instead of a second copy.
        var again = ben.upload("/api/documents", "noch einmal.pdf", pdf, List.of());
        assertThat(again.status()).isEqualTo(200);
        assertThat(ben.json(again).path("duplicate").asBoolean()).isTrue();
        assertThat(ben.json(again).path("document").path("id").asText()).isEqualTo(documentId);
        assertThat(ben.json(again).path("document").path("trash").isObject()).isTrue();

        long trashedVersion = anna.json(trashed).path("version").asLong();
        var restored = ben.postJson("/api/documents/" + documentId + "/restore", "{\"expectedVersion\":"
                + trashedVersion + "}");
        assertThat(restored.status()).as(restored.body()).isEqualTo(200);
        assertThat(ben.json(restored).path("trash").isObject()).isFalse();
        // A repeated restore after a lost response answers with the current state instead of an error.
        assertThat(anna.postJson("/api/documents/" + documentId + "/restore", "{\"expectedVersion\":"
                + trashedVersion + "}").status()).isEqualTo(200);
        assertThat(anna.get("/api/documents?size=100").body()).contains(documentId);
        assertThat(anna.json(anna.get("/api/tasks/" + taskId)).path("source").path("documentTrashed").asBoolean())
                .isFalse();

        // The task itself: out of lists and Today while trashed, back afterwards.
        long taskVersion = anna.json(anna.get("/api/tasks/" + taskId)).path("version").asLong();
        var taskTrashed = anna.postJson("/api/tasks/" + taskId + "/trash", "{\"expectedVersion\":" + taskVersion + "}");
        assertThat(taskTrashed.status()).isEqualTo(200);
        assertThat(ben.get("/api/tasks?includeDone=true").body()).doesNotContain(taskId);
        assertThat(ben.get("/api/today").body()).doesNotContain(taskId);
        assertThat(ben.get("/api/tasks/trash").body()).contains(taskId);
        var complete = ben.postJson("/api/tasks/" + taskId + "/complete", "{\"expectedVersion\":"
                + anna.json(taskTrashed).path("version").asLong() + "}");
        assertThat(complete.status()).isEqualTo(409);
        assertThat(ben.json(complete).path("code").asText()).isEqualTo("task.trashed");
        var taskRestored = ben.postJson("/api/tasks/" + taskId + "/restore", "{\"expectedVersion\":"
                + anna.json(taskTrashed).path("version").asLong() + "}");
        assertThat(taskRestored.status()).isEqualTo(200);
        assertThat(anna.get("/api/today").body()).contains(taskId);
    }

    @Test
    void aTrashedContractSupersedesItsReviewAndItsRestoreReopensTheSameTask() {
        Actor anna = actor(ANNA);
        LocalDate deadline = LocalDate.now(clock).plusDays(60);
        var contract = contracts.create(anna, new CreateContractCommand("Papierkorb Studio " + System.nanoTime(),
                null, null));
        contract = contracts.enterFact(anna, contract.id(), new EnterFactCommand("CANCELLATION_DEADLINE",
                deadline.toString(), null, null, null, null, null, null), contract.version());
        ContractId id = contract.id();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(tasks.findCancellationReviews(id))
                .singleElement().satisfies(task -> assertThat(task.status()).isEqualTo(TaskStatus.OPEN)));
        TaskId review = tasks.findCancellationReviews(id).getFirst().id();

        var trashed = contracts.moveToTrash(anna, id, contract.version());

        assertThat(contractQueries.list(anna)).noneMatch(summary -> summary.id().equals(id));
        assertThat(deadlines.confirmedDeadlines(anna, LocalDate.now(clock)))
                .noneMatch(view -> view.contractId().equals(id));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(tasks.findById(review).orElseThrow()
                .status()).isEqualTo(TaskStatus.CANCELLED));

        contracts.restoreFromTrash(actor(BEN), id, trashed.version());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<Task> occurrences = tasks.findCancellationReviews(id);
            assertThat(occurrences).singleElement().satisfies(task -> {
                assertThat(task.id()).isEqualTo(review);
                assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
            });
        });
        assertThat(contractQueries.list(anna)).anyMatch(summary -> summary.id().equals(id));
    }

    @Test
    void theTrashStateSurvivesExportAndRestore() throws Exception {
        Actor anna = actor(ANNA);
        var contract = contracts.create(anna, new CreateContractCommand("Export Papierkorb " + System.nanoTime(),
                null, null));
        contracts.moveToTrash(anna, contract.id(), contract.version());
        // As stored: PostgreSQL keeps microseconds, so compare with a value read back from the database.
        var trashed = contractQueries.get(anna, contract.id());
        Timestamp trashedAt = jdbc.queryForObject("SELECT trashed_at FROM contracts WHERE id = ?", Timestamp.class,
                contract.id().value());
        assertThat(trashedAt).isNotNull();

        ExportSummary export = createExport.export(anna);
        Path generation = Path.of(export.location());
        String contractsJson = Files.readString(generation.resolve("data/contracts.json"), StandardCharsets.UTF_8);
        assertThat(contractsJson).contains("\"restorableUntil\"");
        String index = Files.readString(generation.resolve("README.md"), StandardCharsets.UTF_8);
        assertThat(index).contains(contract.name() + " (ACTIVE)").contains("in the trash since");

        jdbc.update("DELETE FROM contracts WHERE id = ?", contract.id().value());
        RestoreExport.RestoreReport report = restore.restore(anna, export.location());
        assertThat(report.contractsImported()).isGreaterThanOrEqualTo(1);

        var restored = contractQueries.get(anna, contract.id());
        assertThat(restored.trash()).isEqualTo(trashed.trash());
        assertThat(contractQueries.list(anna)).noneMatch(summary -> summary.id().equals(contract.id()));
        assertThat(contractQueries.trashed(anna)).anyMatch(summary -> summary.id().equals(contract.id()));
    }

    private static byte[] uniquePdf(String label) throws Exception {
        byte[] fixture = fixtureBytes();
        byte[] marker = (" %% " + label + " " + System.nanoTime() + " %% ").getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = new byte[fixture.length + marker.length];
        System.arraycopy(fixture, 0, pdf, 0, fixture.length);
        System.arraycopy(marker, 0, pdf, fixture.length, marker.length);
        return pdf;
    }

    private Actor actor(String username) {
        return new Actor(members.findCredentials(username).orElseThrow().id());
    }
}
