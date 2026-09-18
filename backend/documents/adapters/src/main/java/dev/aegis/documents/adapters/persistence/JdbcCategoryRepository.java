package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.SubjectKind;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Folder tree in PostgreSQL with explicit SQL. Folders are stored with optimistic versions; mappings and the
 * derived effect rows are replaced as sets, so the search views always match the current tree.
 */
@Component
public class JdbcCategoryRepository implements CategoryRepository {
    private final JdbcClient jdbc;

    JdbcCategoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CategoryTree load() {
        Map<UUID, Set<LabelId>> impliedLabels = new LinkedHashMap<>();
        jdbc.sql("SELECT category_id, label_id FROM category_implied_labels").query((rs, row) -> {
            impliedLabels.computeIfAbsent(rs.getObject("category_id", UUID.class), key -> new LinkedHashSet<>())
                    .add(new LabelId(rs.getObject("label_id", UUID.class)));
            return Boolean.TRUE;
        }).list();
        Map<UUID, Set<Subject>> impliedSubjects = new LinkedHashMap<>();
        jdbc.sql("SELECT category_id, kind, member_id FROM category_implied_subjects").query((rs, row) -> {
            UUID member = rs.getObject("member_id", UUID.class);
            impliedSubjects.computeIfAbsent(rs.getObject("category_id", UUID.class), key -> new LinkedHashSet<>())
                    .add(new Subject(SubjectKind.valueOf(rs.getString("kind")),
                            member == null ? null : new MemberId(member)));
            return Boolean.TRUE;
        }).list();
        List<Category> categories = jdbc.sql("""
                SELECT id, parent_id, name, created_by, created_at, updated_at, version FROM categories""")
                .query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    UUID parent = rs.getObject("parent_id", UUID.class);
                    UUID createdBy = rs.getObject("created_by", UUID.class);
                    return Category.rehydrate(new CategoryId(id), parent == null ? null : new CategoryId(parent),
                            rs.getString("name"), impliedLabels.getOrDefault(id, Set.of()),
                            impliedSubjects.getOrDefault(id, Set.of()),
                            createdBy == null ? null : new MemberId(createdBy),
                            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                            rs.getLong("version"));
                }).list();
        return new CategoryTree(categories);
    }

    @Override
    public void save(CategoryTree tree, List<CategoryTree.CategoryEffects> effects) {
        CategoryTree.Changes changes = tree.changes();
        for (Category category : changes.upserts()) {
            upsert(category);
            replaceMappings(category);
        }
        for (CategoryId removed : changes.deletions()) {
            jdbc.sql("DELETE FROM categories WHERE id = ?").param(removed.value()).update();
        }
        replaceEffects(effects);
    }

    private void upsert(Category category) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", category.id().value());
        values.put("parentId", category.parentId().map(CategoryId::value).orElse(null));
        values.put("name", category.name());
        values.put("updatedAt", java.sql.Timestamp.from(category.updatedAt()));
        values.put("version", category.version());
        int updated = jdbc.sql("""
                UPDATE categories SET parent_id = :parentId, name = :name, updated_at = :updatedAt,
                       version = version + 1
                 WHERE id = :id AND version = :version""").params(values).update();
        if (updated > 0) {
            return;
        }
        jdbc.sql("SELECT version FROM categories WHERE id = ?").param(category.id().value()).query(Long.class)
                .optional().ifPresent(stored -> {
                    throw new StaleVersionException("category", category.id().toString(), category.version(),
                            stored);
                });
        values.put("createdBy", category.createdBy().map(MemberId::value).orElse(null));
        values.put("createdAt", java.sql.Timestamp.from(category.createdAt()));
        jdbc.sql("""
                INSERT INTO categories (id, parent_id, name, created_by, created_at, updated_at, version)
                VALUES (:id, :parentId, :name, :createdBy, :createdAt, :updatedAt, 1)""")
                .params(values).update();
    }

    private void replaceMappings(Category category) {
        jdbc.sql("DELETE FROM category_implied_labels WHERE category_id = ?").param(category.id().value()).update();
        jdbc.sql("DELETE FROM category_implied_subjects WHERE category_id = ?").param(category.id().value())
                .update();
        for (LabelId label : category.impliedLabels()) {
            jdbc.sql("INSERT INTO category_implied_labels (category_id, label_id) VALUES (?, ?)")
                    .params(List.of(category.id().value(), label.value())).update();
        }
        for (Subject subject : category.impliedSubjects()) {
            jdbc.sql("INSERT INTO category_implied_subjects (category_id, kind, member_id) VALUES (?, ?, ?)")
                    .params(subjectParams(category.id(), subject)).update();
        }
    }

    private void replaceEffects(List<CategoryTree.CategoryEffects> effects) {
        jdbc.sql("DELETE FROM category_label_effects").update();
        jdbc.sql("DELETE FROM category_subject_effects").update();
        for (CategoryTree.CategoryEffects effect : effects) {
            effect.labels().forEach((label, facet) ->
                    jdbc.sql("INSERT INTO category_label_effects (category_id, label_id, facet) VALUES (?, ?, ?)")
                            .params(List.of(effect.category().value(), label.value(), facet.name())).update());
            for (Subject subject : effect.subjects()) {
                jdbc.sql("INSERT INTO category_subject_effects (category_id, kind, member_id) VALUES (?, ?, ?)")
                        .params(subjectParams(effect.category(), subject)).update();
            }
        }
    }

    private static List<Object> subjectParams(CategoryId category, Subject subject) {
        List<Object> params = new ArrayList<>();
        params.add(category.value());
        params.add(subject.kind().name());
        params.add(subject.memberId().map(MemberId::value).orElse(null));
        return params;
    }
}
