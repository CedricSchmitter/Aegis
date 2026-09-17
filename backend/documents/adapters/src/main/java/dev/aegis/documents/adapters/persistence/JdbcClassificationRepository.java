package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.classification.AssignmentOrigin;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.Confidence;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelAssignment;
import dev.aegis.documents.domain.classification.ReviewState;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Document classifications in PostgreSQL with explicit SQL. The classification version is independent of the
 * document version, so archival progress never invalidates a pending folder or label edit.
 */
@Component
public class JdbcClassificationRepository implements ClassificationRepository {
    private static final String SELECT_ASSIGNMENTS = """
            SELECT dl.document_id, dl.label_id, l.facet, dl.origin, dl.review_state, dl.confidence, dl.source_ref,
                   dl.decided_by, dl.decided_at
              FROM document_labels dl
              JOIN labels l ON l.id = dl.label_id""";

    private final JdbcClient jdbc;

    JdbcClassificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DocumentClassification> findById(DocumentId id) {
        return Optional.ofNullable(findAll(List.of(id)).get(id));
    }

    @Override
    public Map<DocumentId, DocumentClassification> findAll(Collection<DocumentId> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<UUID> values = ids.stream().map(DocumentId::value).toList();
        Map<UUID, List<LabelAssignment>> assignments = new LinkedHashMap<>();
        jdbc.sql(SELECT_ASSIGNMENTS + " WHERE dl.document_id IN (:ids) ORDER BY dl.decided_at, dl.label_id")
                .param("ids", values).query((rs, row) -> {
                    UUID document = rs.getObject("document_id", UUID.class);
                    UUID decidedBy = rs.getObject("decided_by", UUID.class);
                    java.math.BigDecimal confidence = rs.getBigDecimal("confidence");
                    assignments.computeIfAbsent(document, key -> new ArrayList<>()).add(new LabelAssignment(
                            new LabelId(rs.getObject("label_id", UUID.class)),
                            Facet.valueOf(rs.getString("facet")),
                            AssignmentOrigin.valueOf(rs.getString("origin")),
                            ReviewState.valueOf(rs.getString("review_state")),
                            confidence == null ? null : new Confidence(confidence.doubleValue()),
                            rs.getString("source_ref"), decidedBy == null ? null : new MemberId(decidedBy),
                            rs.getTimestamp("decided_at").toInstant()));
                    return Boolean.TRUE;
                }).list();
        Map<DocumentId, DocumentClassification> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT document_id, category_id, updated_by, updated_at, version
                  FROM document_classifications WHERE document_id IN (:ids)""")
                .param("ids", values).query((rs, row) -> {
                    UUID document = rs.getObject("document_id", UUID.class);
                    UUID category = rs.getObject("category_id", UUID.class);
                    UUID updatedBy = rs.getObject("updated_by", UUID.class);
                    var updatedAt = rs.getTimestamp("updated_at");
                    result.put(new DocumentId(document), DocumentClassification.rehydrate(new DocumentId(document),
                            category == null ? null : new CategoryId(category),
                            assignments.getOrDefault(document, List.of()),
                            updatedBy == null ? null : new MemberId(updatedBy),
                            updatedAt == null ? null : updatedAt.toInstant(), rs.getLong("version")));
                    return Boolean.TRUE;
                }).list();
        return result;
    }

    @Override
    public List<DocumentClassification> findByLabel(LabelId label) {
        List<DocumentId> ids = jdbc.sql("SELECT document_id FROM document_labels WHERE label_id = ?")
                .param(label.value()).query((rs, row) -> new DocumentId(rs.getObject("document_id", UUID.class)))
                .list();
        return List.copyOf(findAll(ids).values());
    }

    @Override
    public List<DocumentClassification> findByCategories(Collection<CategoryId> categories) {
        if (categories.isEmpty()) {
            return List.of();
        }
        List<DocumentId> ids = jdbc.sql("""
                SELECT document_id FROM document_classifications WHERE category_id IN (:categories)""")
                .param("categories", categories.stream().map(CategoryId::value).toList())
                .query((rs, row) -> new DocumentId(rs.getObject("document_id", UUID.class))).list();
        return List.copyOf(findAll(ids).values());
    }

    @Override
    public void save(DocumentClassification classification) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("documentId", classification.documentId().value());
        values.put("categoryId", classification.categoryId().map(CategoryId::value).orElse(null));
        values.put("updatedBy", classification.updatedBy().map(MemberId::value).orElse(null));
        values.put("updatedAt", classification.updatedAt().map(java.sql.Timestamp::from).orElse(null));
        values.put("version", classification.version());
        int updated = jdbc.sql("""
                UPDATE document_classifications SET category_id = :categoryId, updated_by = :updatedBy,
                       updated_at = :updatedAt, version = version + 1
                 WHERE document_id = :documentId AND version = :version""").params(values).update();
        if (updated == 0) {
            jdbc.sql("SELECT version FROM document_classifications WHERE document_id = ?")
                    .param(classification.documentId().value()).query(Long.class).optional().ifPresent(stored -> {
                        throw new StaleVersionException("documentClassification",
                                classification.documentId().toString(), classification.version(), stored);
                    });
            jdbc.sql("""
                    INSERT INTO document_classifications (document_id, category_id, updated_by, updated_at, version)
                    VALUES (:documentId, :categoryId, :updatedBy, :updatedAt, 1)""").params(values).update();
        }
        jdbc.sql("DELETE FROM document_labels WHERE document_id = ?").param(classification.documentId().value())
                .update();
        for (LabelAssignment assignment : classification.assignments()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documentId", classification.documentId().value());
            row.put("labelId", assignment.labelId().value());
            row.put("origin", assignment.origin().name());
            row.put("reviewState", assignment.state().name());
            row.put("confidence", assignment.confidence() == null ? null : assignment.confidence().value());
            row.put("sourceRef", assignment.sourceRef());
            row.put("decidedBy", assignment.decidedBy() == null ? null : assignment.decidedBy().value());
            row.put("decidedAt", java.sql.Timestamp.from(assignment.decidedAt()));
            jdbc.sql("""
                    INSERT INTO document_labels (document_id, label_id, origin, review_state, confidence,
                           source_ref, decided_by, decided_at)
                    VALUES (:documentId, :labelId, :origin, :reviewState, :confidence, :sourceRef, :decidedBy,
                           :decidedAt)""").params(row).update();
        }
    }
}
