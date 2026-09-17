package dev.aegis.documents.domain.catalog;

import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A folder of the document tree. The name is user-authored and kept in its original language. Implied labels and
 * subjects are explicit folder mappings that documents inside the folder (and its subfolders) inherit as derived
 * values. Structural rules live in {@link CategoryTree}, which is the only place that mutates folders.
 */
public final class Category {
    private final CategoryId id;
    private CategoryId parentId;
    private String name;
    private final Set<LabelId> impliedLabels;
    private final Set<Subject> impliedSubjects;
    private final MemberId createdBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Category(CategoryId id, CategoryId parentId, String name, Set<LabelId> impliedLabels,
            Set<Subject> impliedSubjects, MemberId createdBy, Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.parentId = parentId;
        this.name = Objects.requireNonNull(name);
        this.impliedLabels = new LinkedHashSet<>(impliedLabels);
        this.impliedSubjects = new LinkedHashSet<>(impliedSubjects);
        this.createdBy = createdBy;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.version = version;
    }

    static Category create(CategoryId id, CategoryId parentId, String name, MemberId createdBy, Instant now) {
        return new Category(id, parentId, name, Set.of(), Set.of(), createdBy, now, now, 0);
    }

    /** Restores a folder from persistence or an export; tree rules are checked when the tree is assembled. */
    public static Category rehydrate(CategoryId id, CategoryId parentId, String name, Set<LabelId> impliedLabels,
            Set<Subject> impliedSubjects, MemberId createdBy, Instant createdAt, Instant updatedAt, long version) {
        return new Category(id, parentId, name, impliedLabels, impliedSubjects, createdBy, createdAt, updatedAt,
                version);
    }

    void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    void moveTo(CategoryId newParent, Instant now) {
        this.parentId = newParent;
        this.updatedAt = now;
    }

    void implies(Set<LabelId> labels, Set<Subject> subjects, Instant now) {
        impliedLabels.clear();
        impliedLabels.addAll(labels);
        impliedSubjects.clear();
        impliedSubjects.addAll(subjects);
        this.updatedAt = now;
    }

    boolean replaceImpliedLabel(LabelId from, LabelId to, Instant now) {
        if (!impliedLabels.remove(from)) {
            return false;
        }
        impliedLabels.add(to);
        this.updatedAt = now;
        return true;
    }

    String nameKey() {
        return CatalogNames.matchKey(name);
    }

    public CategoryId id() {
        return id;
    }

    public Optional<CategoryId> parentId() {
        return Optional.ofNullable(parentId);
    }

    public String name() {
        return name;
    }

    public Set<LabelId> impliedLabels() {
        return Collections.unmodifiableSet(impliedLabels);
    }

    public Set<Subject> impliedSubjects() {
        return Collections.unmodifiableSet(impliedSubjects);
    }

    public Optional<MemberId> createdBy() {
        return Optional.ofNullable(createdBy);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
