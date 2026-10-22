package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.text.ArchiveTextCheck;
import dev.aegis.documents.domain.text.ArchiveTextCheckRepository;
import dev.aegis.documents.domain.text.TextFingerprint;
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
 * Derived text checks in PostgreSQL with explicit SQL. Every save is one upsert; its condition keeps a measurement
 * when a failed check of the same archive document arrives later, so two racing checks cannot lose a measurement.
 * Due measurements that only lack a current fingerprint come after every other due document, least recently tried
 * first, so one archive document that keeps failing cannot hold up the others.
 */
@Component
public class JdbcArchiveTextCheckRepository implements ArchiveTextCheckRepository {
    private static final String SELECT = """
            SELECT document_id, paperless_document_id, recognized_characters, archive_version, checked_at,
                   failed_attempts, next_attempt_at, text_fingerprint
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
                                                  archive_version, checked_at, failed_attempts, next_attempt_at,
                                                  text_fingerprint)
                VALUES (:id, :paperlessId, :characters, :archiveVersion, :checkedAt, :failedAttempts,
                        :nextAttemptAt, :fingerprint)
                ON CONFLICT (document_id) DO UPDATE
                   SET paperless_document_id = EXCLUDED.paperless_document_id,
                       recognized_characters = EXCLUDED.recognized_characters,
                       archive_version = EXCLUDED.archive_version,
                       checked_at = EXCLUDED.checked_at,
                       failed_attempts = EXCLUDED.failed_attempts,
                       next_attempt_at = EXCLUDED.next_attempt_at,
                       text_fingerprint = EXCLUDED.text_fingerprint
                 WHERE EXCLUDED.archive_version IS NOT NULL
                    OR document_text_checks.archive_version IS NULL
                    OR document_text_checks.paperless_document_id <> EXCLUDED.paperless_document_id""")
                .param("id", check.documentId().value())
                .param("paperlessId", check.paperlessDocumentId())
                .param("characters", check.recognizedCharacters())
                .param("archiveVersion", check.archiveVersion())
                .param("checkedAt", Timestamp.from(check.checkedAt()))
                .param("failedAttempts", check.failedAttempts())
                .param("nextAttemptAt", check.nextAttemptAt() == null ? null : Timestamp.from(check.nextAttemptAt()))
                .param("fingerprint", check.fingerprint() == null ? null : check.fingerprint().encode())
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
                        OR (c.archive_version IS NULL AND c.next_attempt_at <= :now)
                        OR (c.archive_version IS NOT NULL AND (c.text_fingerprint IS NULL
                            OR get_byte(c.text_fingerprint, 0) <> :fingerprintVersion)))
                 ORDER BY COALESCE(c.archive_version IS NOT NULL
                                   AND c.paperless_document_id = d.paperless_document_id, FALSE),
                          CASE WHEN c.archive_version IS NOT NULL THEN c.checked_at END,
                          d.archived_at DESC NULLS LAST, d.id
                 LIMIT :limit""")
                .param("archived", ProcessingState.ARCHIVED.name())
                .param("fingerprintVersion", TextFingerprint.VERSION)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, row) -> new DocumentId(rs.getObject("id", UUID.class))).list();
    }

    @Override
    public Map<DocumentId, TextFingerprint> findFingerprints() {
        Map<DocumentId, TextFingerprint> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT c.document_id, c.text_fingerprint
                  FROM document_text_checks c
                  JOIN documents d ON d.id = c.document_id
                 WHERE d.state = :archived
                   AND d.trashed_at IS NULL
                   AND d.paperless_document_id = c.paperless_document_id
                   AND c.text_fingerprint IS NOT NULL""")
                .param("archived", ProcessingState.ARCHIVED.name())
                .query((rs, row) -> {
                    TextFingerprint fingerprint = TextFingerprint.decode(rs.getBytes("text_fingerprint"));
                    if (fingerprint != null) {
                        result.put(new DocumentId(rs.getObject("document_id", UUID.class)), fingerprint);
                    }
                    return null;
                }).list();
        return result;
    }

    private static ArchiveTextCheck map(ResultSet rs) throws SQLException {
        int characters = rs.getInt("recognized_characters");
        Integer recognized = rs.wasNull() ? null : characters;
        boolean version = rs.getBoolean("archive_version");
        Boolean archiveVersion = rs.wasNull() ? null : version;
        Timestamp next = rs.getTimestamp("next_attempt_at");
        return new ArchiveTextCheck(new DocumentId(rs.getObject("document_id", UUID.class)),
                rs.getInt("paperless_document_id"), recognized, archiveVersion,
                rs.getTimestamp("checked_at").toInstant(), rs.getInt("failed_attempts"),
                next == null ? null : next.toInstant(), TextFingerprint.decode(rs.getBytes("text_fingerprint")));
    }
}
