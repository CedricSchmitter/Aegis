package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.shared.DocumentId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Indexed search read model in PostgreSQL. Filtering, counting, sorting and paging run in the database over the
 * effective-label and effective-subject views, so results never depend on what a browser page happens to hold.
 */
@Component
public class JdbcDocumentCatalog implements DocumentCatalog {
    private static final String FROM = " FROM documents d"
            + " LEFT JOIN document_classifications dc ON dc.document_id = d.id";

    private final JdbcClient jdbc;

    JdbcDocumentCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page search(Criteria criteria, Sort sort, int offset, int limit) {
        Conditions conditions = conditions(criteria);
        String order = switch (sort) {
            case NEWEST -> " ORDER BY d.uploaded_at DESC, d.id";
            case OLDEST -> " ORDER BY d.uploaded_at ASC, d.id";
            case TITLE -> " ORDER BY lower(d.title) ASC, d.id";
        };
        Map<String, Object> params = new LinkedHashMap<>(conditions.params());
        params.put("limit", limit);
        params.put("offset", offset);
        List<DocumentId> ids = jdbc.sql("SELECT d.id" + FROM + conditions.where() + order
                        + " LIMIT :limit OFFSET :offset").params(params)
                .query((rs, row) -> new DocumentId(rs.getObject("id", UUID.class))).list();
        Long total = jdbc.sql("SELECT count(*)" + FROM + conditions.where()).params(conditions.params())
                .query(Long.class).single();
        return new Page(ids, total == null ? 0 : total);
    }

    @Override
    public GroupCounts countGroup(Criteria criteria, Group group) {
        Conditions conditions = conditions(criteria);
        Map<String, Long> values = new LinkedHashMap<>();
        if (group == Group.SUBJECT) {
            jdbc.sql("SELECT es.kind, es.member_id, count(*) AS total" + FROM
                            + " JOIN document_effective_subjects es ON es.document_id = d.id"
                            + conditions.where() + " GROUP BY es.kind, es.member_id")
                    .params(conditions.params()).query((rs, row) -> {
                        UUID member = rs.getObject("member_id", UUID.class);
                        String key = member == null ? rs.getString("kind") : "MEMBER:" + member;
                        values.merge(key, rs.getLong("total"), Long::sum);
                        return Boolean.TRUE;
                    }).list();
        } else {
            Map<String, Object> params = new LinkedHashMap<>(conditions.params());
            params.put("facet", group.name());
            jdbc.sql("SELECT el.label_id, count(*) AS total" + FROM
                            + " JOIN document_effective_labels el ON el.document_id = d.id AND el.facet = :facet"
                            + conditions.where() + " GROUP BY el.label_id")
                    .params(params).query((rs, row) -> {
                        values.put(rs.getObject("label_id", UUID.class).toString(), rs.getLong("total"));
                        return Boolean.TRUE;
                    }).list();
        }
        Map<String, Object> missingParams = new LinkedHashMap<>(conditions.params());
        String missingCondition = missingCondition(group, missingParams);
        Long missing = jdbc.sql("SELECT count(*)" + FROM + conditions.where()
                        + (conditions.where().isEmpty() ? " WHERE " : " AND ") + missingCondition)
                .params(missingParams).query(Long.class).single();
        return new GroupCounts(values, missing == null ? 0 : missing);
    }

    @Override
    public long countNotArchived(Criteria criteria) {
        Conditions conditions = conditions(criteria);
        Long count = jdbc.sql("SELECT count(*)" + FROM + conditions.where()
                        + (conditions.where().isEmpty() ? " WHERE " : " AND ") + "d.state <> 'ARCHIVED'")
                .params(conditions.params()).query(Long.class).single();
        return count == null ? 0 : count;
    }

    @Override
    public long countAll() {
        Long count = jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single();
        return count == null ? 0 : count;
    }

    @Override
    public Map<UUID, Long> documentsPerCategory() {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT category_id, count(*) AS total FROM document_classifications
                 WHERE category_id IS NOT NULL GROUP BY category_id""").query((rs, row) -> {
            counts.put(rs.getObject("category_id", UUID.class), rs.getLong("total"));
            return Boolean.TRUE;
        }).list();
        return counts;
    }

    @Override
    public long countUnsorted() {
        Long count = jdbc.sql("SELECT count(*)" + FROM + " WHERE dc.category_id IS NULL").query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    @Override
    public Map<UUID, LabelUsage> labelUsage() {
        Map<UUID, LabelUsage> usage = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT label_id,
                       count(*) FILTER (WHERE review_state IN ('APPLIED', 'CONFIRMED')) AS effective,
                       count(*) FILTER (WHERE review_state = 'SUGGESTED') AS suggested
                  FROM document_labels GROUP BY label_id""").query((rs, row) -> {
            usage.put(rs.getObject("label_id", UUID.class),
                    new LabelUsage(rs.getLong("effective"), rs.getLong("suggested")));
            return Boolean.TRUE;
        }).list();
        return usage;
    }

    private record Conditions(String where, Map<String, Object> params) {
    }

    private Conditions conditions(Criteria criteria) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (criteria.unsortedOnly()) {
            clauses.add("dc.category_id IS NULL");
        }
        if (criteria.categoryScope() != null) {
            if (criteria.categoryScope().isEmpty()) {
                clauses.add("false");
            } else {
                clauses.add("dc.category_id IN (:scope)");
                params.put("scope", List.copyOf(criteria.categoryScope()));
            }
        }
        if (criteria.text() != null) {
            String title = "%" + criteria.text().titleFragment().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            params.put("title", title);
            if (criteria.text().paperlessIds().isEmpty()) {
                clauses.add("lower(d.title) LIKE :title ESCAPE '\\'");
            } else {
                clauses.add("(lower(d.title) LIKE :title ESCAPE '\\' OR d.paperless_document_id IN (:paperlessIds))");
                params.put("paperlessIds", criteria.text().paperlessIds());
            }
        }
        int index = 0;
        for (Map.Entry<Group, ValueFilter> entry : criteria.filters().entrySet()) {
            Group group = entry.getKey();
            ValueFilter filter = entry.getValue();
            if (filter.values().isEmpty()) {
                continue;
            }
            String name = "f" + index++;
            if (group == Group.SUBJECT) {
                clauses.add(subjectClause(filter, name, params));
                continue;
            }
            params.put(name, filter.values().stream().map(UUID::fromString).toList());
            if (filter.matchAll()) {
                params.put(name + "n", filter.values().size());
                clauses.add("(SELECT count(DISTINCT el.label_id) FROM document_effective_labels el"
                        + " WHERE el.document_id = d.id AND el.label_id IN (:" + name + ")) = :" + name + "n");
            } else {
                clauses.add("EXISTS (SELECT 1 FROM document_effective_labels el WHERE el.document_id = d.id"
                        + " AND el.label_id IN (:" + name + "))");
            }
        }
        for (Group group : criteria.missing()) {
            clauses.add(missingCondition(group, params));
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new Conditions(where, params);
    }

    private static String subjectClause(ValueFilter filter, String name, Map<String, Object> params) {
        List<String> alternatives = new ArrayList<>();
        int index = 0;
        for (String value : filter.values()) {
            String key = name + "s" + index++;
            if (value.startsWith("MEMBER:")) {
                params.put(key, UUID.fromString(value.substring("MEMBER:".length())));
                alternatives.add("(es.kind = 'MEMBER' AND es.member_id = :" + key + ")");
            } else {
                params.put(key, value);
                alternatives.add("(es.kind = :" + key + " AND es.member_id IS NULL)");
            }
        }
        return "EXISTS (SELECT 1 FROM document_effective_subjects es WHERE es.document_id = d.id AND ("
                + String.join(" OR ", alternatives) + "))";
    }

    private static String missingCondition(Group group, Map<String, Object> params) {
        if (group == Group.SUBJECT) {
            return "NOT EXISTS (SELECT 1 FROM document_effective_subjects es WHERE es.document_id = d.id)";
        }
        String key = "missing" + group.name();
        params.put(key, group.name());
        return "NOT EXISTS (SELECT 1 FROM document_effective_labels el WHERE el.document_id = d.id"
                + " AND el.facet = :" + key + ")";
    }
}
