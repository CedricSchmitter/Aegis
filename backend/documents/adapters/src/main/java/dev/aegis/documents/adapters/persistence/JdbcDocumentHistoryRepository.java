package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.history.DocumentAction;
import dev.aegis.documents.domain.history.DocumentChange;
import dev.aegis.documents.domain.history.DocumentHistoryRepository;
import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.MemberId;
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

/** Document history rows (ADR-0029); the changed fields are stored as a JSON array. */
@Component
public class JdbcDocumentHistoryRepository implements DocumentHistoryRepository {
    private static final TypeReference<List<FieldChange>> CHANGES = new TypeReference<>() {
    };
    private static final String SELECT = """
            SELECT id, seq, document_id, occurred_at, actor_kind, actor_member, actor_reference, action,
                   changes::text AS changes_json, undo_of
              FROM document_changes""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcDocumentHistoryRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(DocumentChange change) {
        jdbc.sql("""
                INSERT INTO document_changes (id, document_id, occurred_at, actor_kind, actor_member,
                       actor_reference, action, changes, undo_of)
                VALUES (:id, :document, :at, :kind, :member, :reference, :action, CAST(:changes AS jsonb),
                       :undoOf)""")
                .param("id", change.id())
                .param("document", change.documentId().value())
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
    public List<DocumentChange> findByDocument(DocumentId documentId, Long beforeSequence, int limit) {
        return jdbc.sql(SELECT + " WHERE document_id = :document"
                        + " AND (CAST(:before AS BIGINT) IS NULL OR seq < :before) ORDER BY seq DESC LIMIT :limit")
                .param("document", documentId.value())
                .param("before", beforeSequence)
                .param("limit", limit)
                .query(this::row).list();
    }

    @Override
    public Optional<DocumentChange> find(UUID changeId) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(changeId).query(this::row).optional();
    }

    @Override
    public Optional<UUID> undoneBy(UUID changeId) {
        return jdbc.sql("SELECT id FROM document_changes WHERE undo_of = ?").param(changeId).query(UUID.class)
                .optional();
    }

    @Override
    public Instant recordingSince() {
        return jdbc.sql("SELECT recording_since FROM document_history_baseline").query(Timestamp.class).single()
                .toInstant();
    }

    private DocumentChange row(ResultSet rs, int rowNum) throws SQLException {
        UUID member = rs.getObject("actor_member", UUID.class);
        ChangeActor actor = new ChangeActor(ChangeActor.Kind.valueOf(rs.getString("actor_kind")),
                member == null ? null : new MemberId(member), rs.getString("actor_reference"));
        return new DocumentChange(rs.getObject("id", UUID.class),
                new DocumentId(rs.getObject("document_id", UUID.class)), rs.getLong("seq"),
                rs.getTimestamp("occurred_at").toInstant(), actor, DocumentAction.valueOf(rs.getString("action")),
                json.readValue(rs.getString("changes_json"), CHANGES), rs.getObject("undo_of", UUID.class));
    }
}
