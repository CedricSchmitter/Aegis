package dev.aegis.planning.adapters.persistence;

import dev.aegis.planning.domain.history.TaskAction;
import dev.aegis.planning.domain.history.TaskChange;
import dev.aegis.planning.domain.history.TaskHistoryRepository;
import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Task history rows (ADR-0029); the changed fields are stored as a JSON array. */
@Component
public class JdbcTaskHistoryRepository implements TaskHistoryRepository {
    private static final TypeReference<List<FieldChange>> CHANGES = new TypeReference<>() {
    };
    private static final String SELECT = """
            SELECT id, task_id, task_version, occurred_at, actor_kind, actor_member, actor_reference, action,
                   changes::text AS changes_json, undo_of
              FROM task_changes""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcTaskHistoryRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(TaskChange change) {
        jdbc.sql("""
                INSERT INTO task_changes (id, task_id, task_version, occurred_at, actor_kind, actor_member,
                       actor_reference, action, changes, undo_of)
                VALUES (:id, :task, :version, :at, :kind, :member, :reference, :action, CAST(:changes AS jsonb),
                       :undoOf)
                ON CONFLICT (task_id, task_version) DO NOTHING""")
                .param("id", change.id())
                .param("task", change.taskId().value())
                .param("version", change.taskVersion())
                .param("at", Timestamp.from(change.occurredAt()))
                .param("kind", change.actor().kind().name())
                .param("member", change.actor().member() == null ? null : change.actor().member().value())
                .param("reference", change.actor().reference())
                .param("action", change.action().name())
                .param("changes", json.writeValueAsString(change.changes()))
                .param("undoOf", change.undoOf())
                .update();
    }

    @Override
    public List<TaskChange> findByTask(TaskId taskId, Long beforeVersion, int limit) {
        return jdbc.sql(SELECT + " WHERE task_id = :task AND (CAST(:before AS BIGINT) IS NULL OR task_version < :before)"
                        + " ORDER BY task_version DESC LIMIT :limit")
                .param("task", taskId.value())
                .param("before", beforeVersion)
                .param("limit", limit)
                .query(this::row).list();
    }

    @Override
    public Optional<TaskChange> find(UUID changeId) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(changeId).query(this::row).optional();
    }

    @Override
    public Optional<UUID> undoneBy(UUID changeId) {
        return jdbc.sql("SELECT id FROM task_changes WHERE undo_of = ?").param(changeId).query(UUID.class)
                .optional();
    }

    @Override
    public Instant recordingSince() {
        return jdbc.sql("SELECT recording_since FROM task_history_baseline").query(Timestamp.class).single()
                .toInstant();
    }

    private TaskChange row(ResultSet rs, int rowNum) throws SQLException {
        UUID member = rs.getObject("actor_member", UUID.class);
        ChangeActor actor = new ChangeActor(ChangeActor.Kind.valueOf(rs.getString("actor_kind")),
                member == null ? null : new MemberId(member), rs.getString("actor_reference"));
        return new TaskChange(rs.getObject("id", UUID.class), new TaskId(rs.getObject("task_id", UUID.class)),
                rs.getLong("task_version"), rs.getTimestamp("occurred_at").toInstant(), actor,
                TaskAction.valueOf(rs.getString("action")), json.readValue(rs.getString("changes_json"), CHANGES),
                rs.getObject("undo_of", UUID.class));
    }
}
