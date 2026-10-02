package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.Derivation.Range;
import dev.aegis.documents.domain.pages.Derivation.SourceKind;
import dev.aegis.documents.domain.pages.DerivationRepository;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Source relationships of documents created by the page editor, one row per page range. */
@Component
public class JdbcDerivationRepository implements DerivationRepository {
    private static final String SELECT = """
            SELECT document_id, position, first_page, last_page, source_kind, source_document_id, first_source_page,
                   last_source_page, rotation, page_edit_id, created_by, created_at
              FROM document_derivations""";

    private final JdbcClient jdbc;

    JdbcDerivationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(UUID document, UUID pageEdit, MemberId createdBy, Timestamp createdAt, Range range) {
    }

    @Override
    public void insertIfAbsent(Derivation derivation) {
        // Locks the created document's row, so two attempts registering the same output insert its ranges once.
        jdbc.sql("SELECT id FROM documents WHERE id = ? FOR UPDATE").param(derivation.document().value())
                .query(UUID.class).optional();
        boolean present = jdbc.sql("SELECT EXISTS (SELECT 1 FROM document_derivations WHERE document_id = ?)")
                .param(derivation.document().value()).query(Boolean.class).single();
        if (present) {
            return;
        }
        for (int position = 0; position < derivation.ranges().size(); position++) {
            Range range = derivation.ranges().get(position);
            jdbc.sql("""
                    INSERT INTO document_derivations (document_id, position, first_page, last_page, source_kind,
                           source_document_id, first_source_page, last_source_page, rotation, page_edit_id,
                           created_by, created_at)
                    VALUES (:document, :position, :firstPage, :lastPage, :kind, :source, :firstSourcePage,
                           :lastSourcePage, :rotation, :pageEdit, :createdBy, :createdAt)""")
                    .param("document", derivation.document().value())
                    .param("position", position)
                    .param("firstPage", range.firstPage())
                    .param("lastPage", range.lastPage())
                    .param("kind", range.kind().name())
                    .param("source", range.source() == null ? null : range.source().value())
                    .param("firstSourcePage", range.firstSourcePage())
                    .param("lastSourcePage", range.lastSourcePage())
                    .param("rotation", range.rotation())
                    .param("pageEdit", derivation.pageEdit())
                    .param("createdBy", derivation.createdBy().value())
                    .param("createdAt", Timestamp.from(derivation.createdAt()))
                    .update();
        }
    }

    @Override
    public Optional<Derivation> findByDocument(DocumentId document) {
        return group(jdbc.sql(SELECT + " WHERE document_id = ? ORDER BY position").param(document.value())
                .query(this::row).list()).stream().findFirst();
    }

    @Override
    public List<Derivation> findBySource(DocumentId source) {
        return group(jdbc.sql(SELECT
                + " WHERE document_id IN (SELECT document_id FROM document_derivations WHERE source_document_id = ?)"
                + " ORDER BY created_at, document_id, position").param(source.value()).query(this::row).list());
    }

    @Override
    public List<Derivation> findAll() {
        return group(jdbc.sql(SELECT + " ORDER BY document_id, position").query(this::row).list());
    }

    private Row row(ResultSet rs, int rowNumber) throws SQLException {
        UUID source = rs.getObject("source_document_id", UUID.class);
        Range range = new Range(rs.getInt("first_page"), rs.getInt("last_page"),
                SourceKind.valueOf(rs.getString("source_kind")), source == null ? null : new DocumentId(source),
                rs.getInt("first_source_page"), rs.getInt("last_source_page"), rs.getInt("rotation"));
        return new Row(rs.getObject("document_id", UUID.class), rs.getObject("page_edit_id", UUID.class),
                new MemberId(rs.getObject("created_by", UUID.class)), rs.getTimestamp("created_at"), range);
    }

    private static List<Derivation> group(List<Row> rows) {
        Map<UUID, List<Row>> byDocument = new LinkedHashMap<>();
        for (Row row : rows) {
            byDocument.computeIfAbsent(row.document(), ignored -> new ArrayList<>()).add(row);
        }
        List<Derivation> derivations = new ArrayList<>();
        for (List<Row> ranges : byDocument.values()) {
            Row first = ranges.getFirst();
            derivations.add(new Derivation(new DocumentId(first.document()), first.pageEdit(), first.createdBy(),
                    first.createdAt().toInstant(), ranges.stream().map(Row::range).toList()));
        }
        return derivations;
    }
}
