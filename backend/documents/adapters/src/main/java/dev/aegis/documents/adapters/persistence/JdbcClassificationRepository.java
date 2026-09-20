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
    private static final String SELECT_CLASSIFICATIONS = """
            SELECT dc.document_id, dc.category_id, dc.updated_by, dc.updated_at, dc.version,
                   dc.placement_origin, dc.placement_confidence, dc.placement_source_ref, dc.placement_by,
                   dc.placement_at, dc.placed_by_member,
                   dl.label_id, l.facet, dl.origin, dl.review_state, dl.confidence, dl.source_ref,
                   dl.decided_by, dl.decided_at
              FROM document_classifications dc
              LEFT JOIN document_labels dl ON dl.document_id = dc.document_id
              LEFT JOIN labels l ON l.id = dl.label_id""";

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
        Map<DocumentId, DocumentClassification> roots = new LinkedHashMap<>();
        // One statement keeps assignments and their optimistic version in the same PostgreSQL snapshot,
        // including reads outside a transaction and documents that currently have no labels.
        jdbc.sql(SELECT_CLASSIFICATIONS + " WHERE dc.document_id IN (:ids) ORDER BY dl.decided_at, dl.label_id")
                .param("ids", values).query((rs, row) -> {
                    UUID document = rs.getObject("document_id", UUID.class);
                    DocumentId id = new DocumentId(document);
                    if (!roots.containsKey(id)) {
                        UUID category = rs.getObject("category_id", UUID.class);
                        UUID updatedBy = rs.getObject("updated_by", UUID.class);
                        var updatedAt = rs.getTimestamp("updated_at");
                        roots.put(id, DocumentClassification.rehydrate(id,
                                category == null ? null : new CategoryId(category), placement(rs), List.of(),
                                updatedBy == null ? null : new MemberId(updatedBy),
                                updatedAt == null ? null : updatedAt.toInstant(), rs.getLong("version")));
                    }
                    UUID label = rs.getObject("label_id", UUID.class);
                    if (label == null) {
                        return Boolean.TRUE;
                    }
                    UUID decidedBy = rs.getObject("decided_by", UUID.class);
                    java.math.BigDecimal confidence = rs.getBigDecimal("confidence");
                    assignments.computeIfAbsent(document, key -> new ArrayList<>()).add(new LabelAssignment(
                            new LabelId(label),
                            Facet.valueOf(rs.getString("facet")),
                            AssignmentOrigin.valueOf(rs.getString("origin")),
                            ReviewState.valueOf(rs.getString("review_state")),
                            confidence == null ? null : new Confidence(confidence.doubleValue()),
                            rs.getString("source_ref"), decidedBy == null ? null : new MemberId(decidedBy),
                            rs.getTimestamp("decided_at").toInstant()));
                    return Boolean.TRUE;
                }).list();
        Map<DocumentId, DocumentClassification> result = new LinkedHashMap<>();
        roots.forEach((id, root) -> result.put(id, DocumentClassification.rehydrate(id,
                root.categoryId().orElse(null), root.placement(),
                assignments.getOrDefault(id.value(), List.of()), root.updatedBy().orElse(null),
                root.updatedAt().orElse(null), root.version())));
        return result;
    }

    private static DocumentClassification.Placement placement(java.sql.ResultSet rs) throws java.sql.SQLException {
        String origin = rs.getString("placement_origin");
        java.math.BigDecimal confidence = rs.getBigDecimal("placement_confidence");
        UUID by = rs.getObject("placement_by", UUID.class);
        var at = rs.getTimestamp("placement_at");
        return new DocumentClassification.Placement(origin == null ? null : AssignmentOrigin.valueOf(origin),
                confidence == null ? null : new Confidence(confidence.doubleValue()),
                rs.getString("placement_source_ref"), by == null ? null : new MemberId(by),
                at == null ? null : at.toInstant(), rs.getBoolean("placed_by_member"));
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
        DocumentClassification.Placement placement = classification.placement();
        values.put("placementOrigin", placement.origin() == null ? null : placement.origin().name());
        values.put("placementConfidence", placement.confidence() == null ? null : placement.confidence().value());
        values.put("placementSourceRef", placement.sourceRef());
        values.put("placementBy", placement.by() == null ? null : placement.by().value());
        values.put("placementAt", placement.at() == null ? null : java.sql.Timestamp.from(placement.at()));
        values.put("placedByMember", placement.byMember());
        values.put("version", classification.version());
        int updated = jdbc.sql("""
                UPDATE document_classifications SET category_id = :categoryId, updated_by = :updatedBy,
                       updated_at = :updatedAt, placement_origin = :placementOrigin,
                       placement_confidence = :placementConfidence, placement_source_ref = :placementSourceRef,
                       placement_by = :placementBy, placement_at = :placementAt,
                       placed_by_member = :placedByMember, version = version + 1
                 WHERE document_id = :documentId AND version = :version""").params(values).update();
        if (updated == 0) {
            jdbc.sql("SELECT version FROM document_classifications WHERE document_id = ?")
                    .param(classification.documentId().value()).query(Long.class).optional().ifPresent(stored -> {
                        throw new StaleVersionException("documentClassification",
                                classification.documentId().toString(), classification.version(), stored);
                    });
            jdbc.sql("""
                    INSERT INTO document_classifications (document_id, category_id, updated_by, updated_at,
                           placement_origin, placement_confidence, placement_source_ref, placement_by,
                           placement_at, placed_by_member, version)
                    VALUES (:documentId, :categoryId, :updatedBy, :updatedAt, :placementOrigin,
                           :placementConfidence, :placementSourceRef, :placementBy, :placementAt,
                           :placedByMember, 1)""").params(values).update();
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
