package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.application.ports.LegacyHouseholdSubjects;
import dev.aegis.shared.MemberId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Rewrites stored {@code HOUSEHOLD} subjects ("both") into member subjects with explicit SQL (ADR-0017). Only tables
 * of the documents context are touched: stored document subjects, folder implications and their derived effects,
 * and the subject suggestions of document analyses. Record versions are left unchanged on purpose.
 */
@Component
public class JdbcLegacyHouseholdSubjects implements LegacyHouseholdSubjects {
    static final String LEGACY_KIND = "HOUSEHOLD";
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {
    };
    /** Tables with one subject per row and their owner column; the effects are derived but kept consistent. */
    private static final Map<String, String> SUBJECT_TABLES = Map.of("document_subjects", "document_id",
            "category_implied_subjects", "category_id", "category_subject_effects", "category_id");

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcLegacyHouseholdSubjects(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean present() {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM document_subjects WHERE kind = 'HOUSEHOLD')
                    OR EXISTS (SELECT 1 FROM category_implied_subjects WHERE kind = 'HOUSEHOLD')
                    OR EXISTS (SELECT 1 FROM category_subject_effects WHERE kind = 'HOUSEHOLD')
                    OR EXISTS (SELECT 1 FROM document_analyses
                                WHERE subjects @> '[{"kind": "HOUSEHOLD"}]'::jsonb)""")
                .query(Boolean.class).single();
    }

    @Override
    public Replaced replaceWith(List<MemberId> persons) {
        int documents = replaceRows("document_subjects", persons);
        int folders = replaceRows("category_implied_subjects", persons);
        replaceRows("category_subject_effects", persons);
        int analyses = replaceSuggestions(persons);
        return new Replaced(documents, folders, analyses);
    }

    /** @return the number of records that carried the value (at most one such row per record) */
    private int replaceRows(String table, List<MemberId> persons) {
        String owner = SUBJECT_TABLES.get(table);
        for (MemberId person : persons) {
            jdbc.sql("INSERT INTO " + table + " (" + owner + ", kind, member_id) SELECT " + owner
                    + ", 'MEMBER', :person FROM " + table + " WHERE kind = 'HOUSEHOLD' ON CONFLICT DO NOTHING")
                    .param("person", person.value()).update();
        }
        return jdbc.sql("DELETE FROM " + table + " WHERE kind = 'HOUSEHOLD'").update();
    }

    private int replaceSuggestions(List<MemberId> persons) {
        record Row(UUID documentId, String subjects) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT document_id, subjects::text AS subjects_json FROM document_analyses
                 WHERE subjects @> '[{"kind": "HOUSEHOLD"}]'::jsonb ORDER BY document_id""")
                .query((rs, n) -> new Row(rs.getObject("document_id", UUID.class), rs.getString("subjects_json")))
                .list();
        for (Row row : rows) {
            Set<String> documentMembers = new HashSet<>(jdbc.sql("""
                    SELECT member_id::text FROM document_subjects WHERE document_id = :id AND kind = 'MEMBER'""")
                    .param("id", row.documentId()).query(String.class).list());
            List<Map<String, Object>> migrated = replaceSuggestions(json.readValue(row.subjects(), ROWS), persons,
                    documentMembers);
            jdbc.sql("UPDATE document_analyses SET subjects = CAST(:subjects AS jsonb) WHERE document_id = :id")
                    .param("subjects", json.writeValueAsString(migrated)).param("id", row.documentId()).update();
        }
        return rows.size();
    }

    /**
     * Each {@code HOUSEHOLD} suggestion becomes the same suggestion (confidence, quote, decision, who decided and when)
     * for every person, in its place. A suggestion that already names the person wins, and a pending one is not added
     * for a person the document is already about.
     */
    static List<Map<String, Object>> replaceSuggestions(List<Map<String, Object>> entries, List<MemberId> persons,
            Set<String> documentMembers) {
        Set<String> suggested = new HashSet<>();
        entries.stream().filter(entry -> "MEMBER".equals(entry.get("kind")) && entry.get("memberId") != null)
                .forEach(entry -> suggested.add(entry.get("memberId").toString()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (!LEGACY_KIND.equals(entry.get("kind"))) {
                result.add(entry);
                continue;
            }
            boolean pending = "SUGGESTED".equals(entry.get("decision"));
            for (MemberId person : persons) {
                String member = person.toString();
                if (suggested.contains(member) || pending && documentMembers.contains(member)) {
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>(entry);
                copy.put("kind", "MEMBER");
                copy.put("memberId", member);
                result.add(copy);
                suggested.add(member);
            }
        }
        return result;
    }
}
