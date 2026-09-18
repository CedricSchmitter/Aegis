package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelStatus;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Label vocabulary in PostgreSQL with explicit SQL: the aggregate root carries an optimistic version, aliases and
 * normalized match keys are replaced as a set, and the unique key index turns a concurrent duplicate into the
 * stable {@code label.exists} error.
 */
@Component
public class JdbcLabelRepository implements LabelRepository {
    private static final String SELECT_LABELS = """
            SELECT id, facet, builtin_key, name, status, merged_into, created_by, created_at, updated_at, version
              FROM labels""";

    private final JdbcClient jdbc;

    JdbcLabelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LabelVocabulary load() {
        Map<UUID, List<String>> aliases = aliases(null);
        List<Label> labels = jdbc.sql(SELECT_LABELS).query((rs, row) -> toDomain(rs, aliases)).list();
        return new LabelVocabulary(labels);
    }

    @Override
    public Optional<Label> findById(LabelId id) {
        Map<UUID, List<String>> aliases = aliases(id.value());
        return jdbc.sql(SELECT_LABELS + " WHERE id = ?").param(id.value())
                .query((rs, row) -> toDomain(rs, aliases)).optional();
    }

    @Override
    public void save(Label label) {
        int updated = jdbc.sql("""
                UPDATE labels SET facet = :facet, builtin_key = :builtinKey, name = :name, status = :status,
                       merged_into = :mergedInto, updated_at = :updatedAt, version = version + 1
                 WHERE id = :id AND version = :version""")
                .params(columns(label)).update();
        if (updated == 0) {
            jdbc.sql("SELECT version FROM labels WHERE id = ?").param(label.id().value()).query(Long.class)
                    .optional().ifPresent(stored -> {
                        throw new StaleVersionException("label", label.id().toString(), label.version(), stored);
                    });
            Map<String, Object> values = new LinkedHashMap<>(columns(label));
            values.put("createdBy", label.createdBy().map(MemberId::value).orElse(null));
            values.put("createdAt", java.sql.Timestamp.from(label.createdAt()));
            jdbc.sql("""
                    INSERT INTO labels (id, facet, builtin_key, name, status, merged_into, created_by,
                           created_at, updated_at, version)
                    VALUES (:id, :facet, :builtinKey, :name, :status, :mergedInto, :createdBy, :createdAt,
                           :updatedAt, 1)""").params(values).update();
        }
        replaceAliasesAndKeys(label);
    }

    @Override
    public void delete(LabelId id) {
        jdbc.sql("DELETE FROM labels WHERE id = ?").param(id.value()).update();
    }

    private void replaceAliasesAndKeys(Label label) {
        jdbc.sql("DELETE FROM label_aliases WHERE label_id = ?").param(label.id().value()).update();
        jdbc.sql("DELETE FROM label_keys WHERE label_id = ?").param(label.id().value()).update();
        List<String> aliases = label.aliases();
        for (int position = 0; position < aliases.size(); position++) {
            jdbc.sql("INSERT INTO label_aliases (label_id, position, alias) VALUES (?, ?, ?)")
                    .params(List.of(label.id().value(), position, aliases.get(position))).update();
        }
        for (String key : label.matchKeys()) {
            try {
                jdbc.sql("INSERT INTO label_keys (facet, match_key, label_id) VALUES (?, ?, ?)")
                        .params(List.of(label.facet().name(), key, label.id().value())).update();
            } catch (DataIntegrityViolationException duplicate) {
                throw new InvalidInputException("label.exists", "A label with this name already exists",
                        Map.of("matchKey", key, "facet", label.facet().name()));
            }
        }
    }

    private Map<String, Object> columns(Label label) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", label.id().value());
        values.put("facet", label.facet().name());
        values.put("builtinKey", label.builtinKey().orElse(null));
        values.put("name", label.name().orElse(null));
        values.put("status", label.status().name());
        values.put("mergedInto", label.mergedInto().map(LabelId::value).orElse(null));
        values.put("updatedAt", java.sql.Timestamp.from(label.updatedAt()));
        values.put("version", label.version());
        return values;
    }

    private Map<UUID, List<String>> aliases(UUID labelId) {
        Map<UUID, List<String>> aliases = new LinkedHashMap<>();
        String sql = "SELECT label_id, alias FROM label_aliases"
                + (labelId == null ? "" : " WHERE label_id = ?") + " ORDER BY label_id, position";
        var spec = jdbc.sql(sql);
        if (labelId != null) {
            spec = spec.param(labelId);
        }
        spec.query((rs, row) -> {
            aliases.computeIfAbsent(rs.getObject("label_id", UUID.class), key -> new ArrayList<>())
                    .add(rs.getString("alias"));
            return Boolean.TRUE;
        }).list();
        return aliases;
    }

    private static Label toDomain(ResultSet rs, Map<UUID, List<String>> aliases) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID mergedInto = rs.getObject("merged_into", UUID.class);
        UUID createdBy = rs.getObject("created_by", UUID.class);
        return Label.rehydrate(new LabelId(id), Facet.valueOf(rs.getString("facet")), rs.getString("builtin_key"),
                rs.getString("name"), aliases.getOrDefault(id, List.of()),
                LabelStatus.valueOf(rs.getString("status")), mergedInto == null ? null : new LabelId(mergedInto),
                createdBy == null ? null : new MemberId(createdBy),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
