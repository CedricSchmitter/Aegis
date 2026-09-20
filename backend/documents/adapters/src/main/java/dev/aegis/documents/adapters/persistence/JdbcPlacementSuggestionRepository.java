package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.placement.PlacementSuggestion;
import dev.aegis.documents.domain.placement.PlacementSuggestionRepository;
import dev.aegis.documents.domain.placement.PlacementTarget;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Pending placement suggestions and remembered rejections in PostgreSQL with explicit SQL. A suggestion whose
 * folder or anchor is deleted disappears with it (foreign keys), so a proposal can never point into a tree that no
 * longer exists.
 */
@Component
public class JdbcPlacementSuggestionRepository implements PlacementSuggestionRepository {
    private static final TypeReference<List<String>> SEGMENTS = new TypeReference<>() {
    };
    private static final String SELECT = """
            SELECT document_id, target_key, folder_id, anchor_id, new_path::text AS new_path_json, generation,
                   confidence, evidence, run_ref, model, created_at
              FROM document_placement_suggestions""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcPlacementSuggestionRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<PlacementSuggestion> findById(DocumentId id) {
        return Optional.ofNullable(findAll(List.of(id)).get(id));
    }

    @Override
    public Map<DocumentId, PlacementSuggestion> findAll(Collection<DocumentId> ids) {
        Map<DocumentId, PlacementSuggestion> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql(SELECT + " WHERE document_id IN (:ids)")
                .param("ids", ids.stream().map(DocumentId::value).toList())
                .query((rs, row) -> map(rs)).list()
                .forEach(suggestion -> result.put(suggestion.documentId(), suggestion));
        return result;
    }

    @Override
    public List<PlacementSuggestion> findByKey(String key) {
        return jdbc.sql(SELECT + " WHERE target_key = ? ORDER BY created_at, document_id").param(key)
                .query((rs, row) -> map(rs)).list();
    }

    @Override
    public Map<String, Integer> countByKeys(Collection<String> keys) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return counts;
        }
        jdbc.sql("""
                SELECT target_key, count(*) AS documents FROM document_placement_suggestions
                 WHERE target_key IN (:keys) GROUP BY target_key""")
                .param("keys", List.copyOf(keys))
                .query((rs, row) -> Map.entry(rs.getString("target_key"), rs.getInt("documents"))).list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    @Override
    public void save(PlacementSuggestion suggestion) {
        PlacementTarget target = suggestion.target();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("documentId", suggestion.documentId().value());
        values.put("targetKey", suggestion.key());
        values.put("folderId", target.folderId().map(CategoryId::value).orElse(null));
        values.put("anchorId", target.anchorId().map(CategoryId::value).orElse(null));
        values.put("newPath", json.writeValueAsString(target.newPath()));
        values.put("generation", suggestion.generation());
        values.put("confidence", suggestion.confidence());
        values.put("evidence", suggestion.evidence());
        values.put("runRef", suggestion.runRef());
        values.put("model", suggestion.model());
        values.put("createdAt", Timestamp.from(suggestion.createdAt()));
        jdbc.sql("""
                INSERT INTO document_placement_suggestions (document_id, target_key, folder_id, anchor_id, new_path,
                       generation, confidence, evidence, run_ref, model, created_at)
                VALUES (:documentId, :targetKey, :folderId, :anchorId, CAST(:newPath AS JSONB), :generation,
                       :confidence, :evidence, :runRef, :model, :createdAt)
                ON CONFLICT (document_id) DO UPDATE SET target_key = EXCLUDED.target_key,
                       folder_id = EXCLUDED.folder_id, anchor_id = EXCLUDED.anchor_id,
                       new_path = EXCLUDED.new_path, generation = EXCLUDED.generation,
                       confidence = EXCLUDED.confidence, evidence = EXCLUDED.evidence, run_ref = EXCLUDED.run_ref,
                       model = EXCLUDED.model, created_at = EXCLUDED.created_at""").params(values).update();
    }

    @Override
    public void delete(DocumentId id) {
        jdbc.sql("DELETE FROM document_placement_suggestions WHERE document_id = ?").param(id.value()).update();
    }

    @Override
    public Set<String> rejectedKeys(DocumentId id) {
        return rejectedKeys(List.of(id)).getOrDefault(id, Set.of());
    }

    @Override
    public Map<DocumentId, Set<String>> rejectedKeys(Collection<DocumentId> ids) {
        Map<DocumentId, Set<String>> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                SELECT document_id, target_key FROM document_placement_rejections WHERE document_id IN (:ids)""")
                .param("ids", ids.stream().map(DocumentId::value).toList())
                .query((rs, row) -> Map.entry(new DocumentId(rs.getObject("document_id", UUID.class)),
                        rs.getString("target_key")))
                .list().forEach(entry -> result.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>())
                        .add(entry.getValue()));
        return result;
    }

    @Override
    public void reject(DocumentId id, String key, MemberId by, Instant at) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("documentId", id.value());
        values.put("targetKey", key);
        values.put("rejectedBy", by == null ? null : by.value());
        values.put("rejectedAt", Timestamp.from(at));
        jdbc.sql("""
                INSERT INTO document_placement_rejections (document_id, target_key, rejected_by, rejected_at)
                VALUES (:documentId, :targetKey, :rejectedBy, :rejectedAt)
                ON CONFLICT (document_id, target_key) DO NOTHING""").params(values).update();
    }

    private PlacementSuggestion map(ResultSet rs) throws SQLException {
        UUID folder = rs.getObject("folder_id", UUID.class);
        UUID anchor = rs.getObject("anchor_id", UUID.class);
        List<String> path = json.readValue(rs.getString("new_path_json"), SEGMENTS);
        PlacementTarget target = folder != null ? PlacementTarget.existing(new CategoryId(folder))
                : PlacementTarget.path(anchor == null ? null : new CategoryId(anchor), path);
        BigDecimal confidence = rs.getBigDecimal("confidence");
        return new PlacementSuggestion(new DocumentId(rs.getObject("document_id", UUID.class)),
                rs.getInt("generation"), target, confidence == null ? null : confidence.doubleValue(),
                rs.getString("evidence"), rs.getString("run_ref"), rs.getString("model"),
                rs.getTimestamp("created_at").toInstant());
    }
}
