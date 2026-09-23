package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.text.ArchiveTextCheck;
import dev.aegis.documents.domain.text.ArchiveTextCheckRepository;
import dev.aegis.shared.DocumentId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Derived text checks in PostgreSQL with explicit SQL. Every save is one upsert; its condition keeps a measured count
 * when a failed check of the same archive document arrives later, so two racing checks cannot lose a measurement.
 */
@Component
public class JdbcArchiveTextCheckRepository implements ArchiveTextCheckRepository {
    private static final String SELECT = """
            SELECT document_id, paperless_document_id, recognized_characters, checked_at, failed_attempts,
                   next_attempt_at
              FROM document_text_checks""";

    private final JdbcClient jdbc;

    JdbcArchiveTextCheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ArchiveTextCheck> find(DocumentId documentId) {
        return Optional.ofNullable(findAll(List.of(documentId)).get(documentId));
    }

    @Override
    public Map<DocumentId, ArchiveTextCheck> findAll(Collection<DocumentId> documentIds) {
        Map<DocumentId, ArchiveTextCheck> result = new LinkedHashMap<>();
        if (documentIds.isEmpty()) {
            return result;
        }
        jdbc.sql(SELECT + " WHERE document_id IN (:ids)")
                .param("ids", documentIds.stream().map(DocumentId::value).toList())
                .query((rs, row) -> map(rs)).list()
                .forEach(check -> result.put(check.documentId(), check));
        return result;
    }

    @Override
    public void save(ArchiveTextCheck check) {
        jdbc.sql("""
                INSERT INTO document_text_checks (document_id, paperless_document_id, recognized_characters,
                                                  checked_at, failed_attempts, next_attempt_at)
                VALUES (:id, :paperlessId, :characters, :checkedAt, :failedAttempts, :nextAttemptAt)
                ON CONFLICT (document_id) DO UPDATE
                   SET paperless_document_id = EXCLUDED.paperless_document_id,
                       recognized_characters = EXCLUDED.recognized_characters,
                       checked_at = EXCLUDED.checked_at,
                       failed_attempts = EXCLUDED.failed_attempts,
                       next_attempt_at = EXCLUDED.next_attempt_at
                 WHERE EXCLUDED.recognized_characters IS NOT NULL
                    OR document_text_checks.recognized_characters IS NULL
                    OR document_text_checks.paperless_document_id <> EXCLUDED.paperless_document_id""")
                .param("id", check.documentId().value())
                .param("paperlessId", check.paperlessDocumentId())
                .param("characters", check.recognizedCharacters())
                .param("checkedAt", Timestamp.from(check.checkedAt()))
                .param("failedAttempts", check.failedAttempts())
                .param("nextAttemptAt", check.nextAttemptAt() == null ? null : Timestamp.from(check.nextAttemptAt()))
                .update();
    }

    @Override
    public List<DocumentId> findDue(Instant now, int limit) {
        return jdbc.sql("""
                SELECT d.id
                  FROM documents d
                  LEFT JOIN document_text_checks c ON c.document_id = d.id
                 WHERE d.state = :archived
                   AND d.paperless_document_id IS NOT NULL
                   AND (c.document_id IS NULL
                        OR c.paperless_document_id <> d.paperless_document_id
                        OR (c.recognized_characters IS NULL AND c.next_attempt_at <= :now))
                 ORDER BY d.archived_at DESC NULLS LAST, d.id
                 LIMIT :limit""")
                .param("archived", ProcessingState.ARCHIVED.name())
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, row) -> new DocumentId(rs.getObject("id", UUID.class))).list();
    }

    private static ArchiveTextCheck map(ResultSet rs) throws SQLException {
        int characters = rs.getInt("recognized_characters");
        Integer recognized = rs.wasNull() ? null : characters;
        Timestamp next = rs.getTimestamp("next_attempt_at");
        return new ArchiveTextCheck(new DocumentId(rs.getObject("document_id", UUID.class)),
                rs.getInt("paperless_document_id"), recognized, rs.getTimestamp("checked_at").toInstant(),
                rs.getInt("failed_attempts"), next == null ? null : next.toInstant());
    }
}
