package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.application.api.TaskCommands.CreateTaskCommand;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.shared.Actor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Change history and undo (ADR-0029) against PostgreSQL and the HTTP API: entries commit and roll back with their
 * change, an undo is a new entry bound to the version the member saw, and history leaves with a deleted record.
 */
class ChangeHistoryIT extends PostgresIT {
    @Autowired TaskCommands taskCommands;
    @Autowired MemberCredentialsQuery members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @LocalServerPort int port;

    private Actor anna() {
        return new Actor(members.findCredentials(ANNA).orElseThrow().id());
    }

    private static String version(long version) {
        return "{\"expectedVersion\":" + version + "}";
    }

    @Test
    void aTaskEditIsRecordedAndUndoneOverHttpWithoutOverwritingALaterEdit() {
        ApiClient anna = new ApiClient(port);
        assertThat(anna.login(ANNA, PASSWORD)).isTrue();
        ApiClient ben = new ApiClient(port);
        assertThat(ben.login(BEN, PASSWORD)).isTrue();
        var created = anna.postJson("/api/tasks", "{\"title\":\"Heizung entlüften\"}");
        assertThat(created.status()).isEqualTo(201);
        String taskId = anna.json(created).path("id").asText();
        long v0 = anna.json(created).path("version").asLong();

        var edited = ben.putJson("/api/tasks/" + taskId, "{\"title\":\"Heizkörper entlüften\",\"note\":\"Schlüssel im Keller\","
                + "\"expectedVersion\":" + v0 + "}");
        assertThat(edited.status()).as(edited.body()).isEqualTo(200);
        long v1 = ben.json(edited).path("version").asLong();
        var planned = anna.postJson("/api/tasks/" + taskId + "/plan", "{\"plannedDate\":\"2026-11-07\","
                + "\"expectedVersion\":" + v1 + "}");
        long v2 = anna.json(planned).path("version").asLong();

        JsonNode history = anna.json(anna.get("/api/tasks/" + taskId + "/history"));
        assertThat(history.path("recordingSince").asText()).isNotBlank();
        JsonNode changes = history.path("changes");
        assertThat(changes).hasSize(3);
        assertThat(changes.get(0).path("action").asText()).isEqualTo("PLANNED");
        JsonNode edit = changes.get(1);
        assertThat(edit.path("action").asText()).isEqualTo("EDITED");
        assertThat(edit.path("actorKind").asText()).isEqualTo("MEMBER");
        assertThat(edit.path("actorMemberId").asText()).isEqualTo(members.findCredentials(BEN).orElseThrow().id()
                .toString());
        assertThat(edit.path("changes").get(0).path("field").asText()).isEqualTo("title");
        assertThat(edit.path("changes").get(0).path("before").asText()).isEqualTo("Heizung entlüften");
        assertThat(edit.path("changes").get(1).path("field").asText()).isEqualTo("note");
        assertThat(edit.path("changes").get(1).path("before").isNull()).isTrue();
        assertThat(edit.path("undoable").asBoolean()).isTrue();
        String editId = edit.path("id").asText();

        var stale = anna.postJson("/api/tasks/" + taskId + "/history/" + editId + "/undo", version(v1));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(anna.json(stale).path("code").asText()).isEqualTo("version.conflict");

        var undone = anna.postJson("/api/tasks/" + taskId + "/history/" + editId + "/undo", version(v2));
        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(anna.json(undone).path("title").asText()).isEqualTo("Heizung entlüften");
        assertThat(anna.json(undone).path("note").asText()).isEmpty();
        assertThat(anna.json(undone).path("plannedDate").asText()).isEqualTo("2026-11-07");
        long v3 = anna.json(undone).path("version").asLong();

        var again = ben.postJson("/api/tasks/" + taskId + "/history/" + editId + "/undo", version(v3));
        assertThat(again.status()).isEqualTo(409);
        assertThat(ben.json(again).path("code").asText()).isEqualTo("history.undo.alreadyUndone");

        String planId = changes.get(0).path("id").asText();
        var replanned = ben.postJson("/api/tasks/" + taskId + "/plan", "{\"plannedDate\":\"2026-11-14\","
                + "\"expectedVersion\":" + v3 + "}");
        long v4 = ben.json(replanned).path("version").asLong();
        var overwritten = anna.postJson("/api/tasks/" + taskId + "/history/" + planId + "/undo", version(v4));
        assertThat(overwritten.status()).isEqualTo(409);
        assertThat(anna.json(overwritten).path("code").asText()).isEqualTo("history.undo.changedSince");
        assertThat(anna.json(overwritten).path("fields").asText()).isEqualTo("plannedDate");

        JsonNode after = ben.json(ben.get("/api/tasks/" + taskId + "/history?limit=2"));
        assertThat(after.path("changes").get(1).path("action").asText()).isEqualTo("UNDONE");
        assertThat(after.path("changes").get(1).path("undoOf").asText()).isEqualTo(editId);
        assertThat(after.path("nextBeforeVersion").isNull()).isFalse();
        assertThat(ben.get("/api/tasks/" + taskId + "/history?limit=0").status()).isEqualTo(400);
    }

    @Test
    void anEntryRollsBackWithItsChangeAndEachSavedVersionIsRecordedOnce() {
        TaskSummary task = taskCommands.create(anna(), new CreateTaskCommand("Atomar", "", null, null, null, null));
        UUID id = task.id().value();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            taskCommands.edit(anna(), task.id(), "Nie gespeichert", "", task.version());
            assertThat(count(id)).as("visible inside the transaction").isEqualTo(2);
            throw new IllegalStateException("simulated failure after the change");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, id)).isEqualTo("Atomar");
        taskCommands.edit(anna(), task.id(), "Gespeichert", "", task.version());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT task_version) FROM task_changes WHERE task_id = ?",
                Integer.class, id)).isEqualTo(count(id)).isEqualTo(2);
    }

    @Test
    void aTaskDeletedPermanentlyTakesItsHistoryWithIt() {
        TaskSummary task = taskCommands.create(anna(), new CreateTaskCommand("Weg damit", "", null, null, null, null));
        TaskSummary edited = taskCommands.edit(anna(), task.id(), "Weg damit!", "", task.version());
        TaskSummary trashed = taskCommands.moveToTrash(anna(), task.id(), edited.version());
        assertThat(count(task.id().value())).isEqualTo(3);

        taskCommands.purge(anna(), task.id(), trashed.version());

        assertThat(count(task.id().value())).isZero();
    }

    private int count(UUID taskId) {
        return jdbc.queryForObject("SELECT count(*) FROM task_changes WHERE task_id = ?", Integer.class, taskId);
    }
}
