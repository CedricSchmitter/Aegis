package dev.aegis.documents.domain.catalog;

import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The folder hierarchy as one consistency boundary for structural rules: unique sibling names, bounded depth and no
 * cycles. Callers load the whole tree under a lock, apply one operation and persist {@link #changes()}. Each folder
 * keeps its own version so that unrelated concurrent edits do not conflict for the user.
 */
public final class CategoryTree {
    public static final int MAX_DEPTH = 8;
    public static final int MAX_IMPLIED_LABELS = 20;

    /** Labels and subjects a folder passes on, each with the nearest folder that implies it. */
    public record Implications(Map<LabelId, CategoryId> labels, Map<Subject, CategoryId> subjects) {
        public static final Implications NONE = new Implications(Map.of(), Map.of());

        public Implications {
            labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
            subjects = Collections.unmodifiableMap(new LinkedHashMap<>(subjects));
        }
    }

    /** Derived row set for search: everything documents placed directly in {@code category} inherit. */
    public record CategoryEffects(CategoryId category, Map<LabelId, Facet> labels, Set<Subject> subjects) {
    }

    /** Result of removing a folder: where its documents go (empty = Unsorted) and which subfolders moved up. */
    public record Removal(CategoryId removed, Optional<CategoryId> contentsTarget, List<CategoryId> movedChildren) {
    }

    /** Folders to insert or update and folders to delete, in a safe persistence order. */
    public record Changes(List<Category> upserts, List<CategoryId> deletions) {
    }

    /**
     * How far a proposed path already exists: {@code deepest} is the last folder that matched by normalized sibling
     * name ({@code null} = top level), {@code missing} are the segments that would have to be created.
     */
    public record PathResolution(CategoryId deepest, List<String> missing) {
        public PathResolution {
            missing = List.copyOf(missing);
        }

        public boolean complete() {
            return missing.isEmpty();
        }
    }

    /** The folder a created path ends in and the folders that had to be created for it. */
    public record CreatedPath(CategoryId target, List<Category> created) {
    }

    private final Map<CategoryId, Category> nodes = new LinkedHashMap<>();
    private final Set<CategoryId> touched = new LinkedHashSet<>();
    private final Set<CategoryId> removed = new LinkedHashSet<>();

    public CategoryTree(Collection<Category> categories) {
        categories.forEach(category -> nodes.put(category.id(), category));
        for (Category category : categories) {
            category.parentId().ifPresent(parent -> {
                if (!nodes.containsKey(parent)) {
                    throw new IllegalStateException("folder " + category.id() + " has unknown parent " + parent);
                }
            });
            if (depth(category.id()) == Integer.MAX_VALUE) {
                throw new IllegalStateException("folder tree is cyclic at " + category.id());
            }
        }
    }

    public Category create(CategoryId id, CategoryId parentId, String name, MemberId createdBy, Instant now) {
        if (nodes.containsKey(id)) {
            throw new InvalidInputException("category.id.taken", "Folder id already exists");
        }
        String validName = CatalogNames.requireName(name, "category");
        if (parentId != null) {
            require(parentId);
            if (depth(parentId) + 1 > MAX_DEPTH) {
                throw new InvalidInputException("category.depth", "Folders can be nested at most " + MAX_DEPTH
                        + " levels deep");
            }
        }
        requireUniqueSiblingName(parentId, validName, null);
        Category category = Category.create(id, parentId, validName, Objects.requireNonNull(createdBy), now);
        nodes.put(id, category);
        touched.add(id);
        return category;
    }

    public Category rename(CategoryId id, String name, long expectedVersion, Instant now) {
        Category category = requireVersion(id, expectedVersion);
        String validName = CatalogNames.requireName(name, "category");
        if (!validName.equals(category.name())) {
            requireUniqueSiblingName(category.parentId().orElse(null), validName, id);
            category.rename(validName, now);
            touched.add(id);
        }
        return category;
    }

    public Category move(CategoryId id, CategoryId newParentId, long expectedVersion, Instant now) {
        Category category = requireVersion(id, expectedVersion);
        if (Objects.equals(category.parentId().orElse(null), newParentId)) {
            return category;
        }
        if (newParentId != null) {
            require(newParentId);
            if (subtree(id).contains(newParentId)) {
                throw new InvalidInputException("category.cycle", "A folder cannot be moved into itself");
            }
            if (depth(newParentId) + height(id) > MAX_DEPTH) {
                throw new InvalidInputException("category.depth", "Folders can be nested at most " + MAX_DEPTH
                        + " levels deep");
            }
        }
        requireUniqueSiblingName(newParentId, category.name(), id);
        category.moveTo(newParentId, now);
        touched.add(id);
        return category;
    }

    public Category changeImplications(CategoryId id, Set<LabelId> labels, Set<Subject> subjects,
            Function<LabelId, Facet> facetOf, long expectedVersion, Instant now) {
        Category category = requireVersion(id, expectedVersion);
        if (labels.size() > MAX_IMPLIED_LABELS) {
            throw new InvalidInputException("category.implication.tooMany",
                    "A folder can imply at most " + MAX_IMPLIED_LABELS + " labels");
        }
        long types = labels.stream().filter(label -> facetOf.apply(label) == Facet.DOCUMENT_TYPE).count();
        if (types > 1) {
            throw new InvalidInputException("category.implication.multipleTypes",
                    "A folder can imply at most one document type");
        }
        category.implies(labels, subjects, now);
        touched.add(id);
        return category;
    }

    /**
     * Removes a folder. Its subfolders move to its parent; its documents are to be moved to
     * {@link Removal#contentsTarget()} by the caller. Nothing else is deleted.
     */
    public Removal remove(CategoryId id, long expectedVersion, Instant now) {
        Category category = requireVersion(id, expectedVersion);
        CategoryId parent = category.parentId().orElse(null);
        List<Category> children = childrenOf(id);
        for (Category child : children) {
            for (Category sibling : childrenOf(parent)) {
                if (!sibling.id().equals(id) && sibling.nameKey().equals(child.nameKey())) {
                    throw new InvalidInputException("category.delete.nameConflict",
                            "A subfolder has the same name as a folder at the target level",
                            Map.of("conflictingId", child.id().toString()));
                }
            }
        }
        List<CategoryId> moved = new ArrayList<>();
        for (Category child : children) {
            child.moveTo(parent, now);
            touched.add(child.id());
            moved.add(child.id());
        }
        nodes.remove(id);
        touched.remove(id);
        removed.add(id);
        return new Removal(id, Optional.ofNullable(parent), moved);
    }

    /** Rewrites folder mappings after a label merge. */
    public void replaceImpliedLabel(LabelId from, LabelId to, Instant now) {
        for (Category category : nodes.values()) {
            if (category.replaceImpliedLabel(from, to, now)) {
                touched.add(category.id());
            }
        }
    }

    /**
     * Matches {@code segments} below {@code anchor} ({@code null} = top level) against existing folders by
     * normalized sibling name. Matching stops at the first segment without a sibling, so an existing folder is
     * always reused instead of a near-duplicate that differs only in case, whitespace or Unicode form.
     */
    public PathResolution resolve(CategoryId anchor, List<String> segments) {
        CategoryId current = anchor == null ? null : require(anchor).id();
        List<String> missing = new ArrayList<>();
        for (String segment : segments) {
            if (!missing.isEmpty()) {
                missing.add(segment);
                continue;
            }
            Category match = null;
            for (Category child : childrenOf(current)) {
                if (child.nameKeyEquals(segment)) {
                    match = child;
                    break;
                }
            }
            if (match == null) {
                missing.add(segment);
            } else {
                current = match.id();
            }
        }
        return new PathResolution(current, missing);
    }

    /**
     * Creates the folders of {@code segments} that do not exist yet below {@code anchor} and returns the folder the
     * path ends in. Existing folders are reused; depth and sibling-name rules apply to every created folder.
     */
    public CreatedPath createPath(CategoryId anchor, List<String> segments, MemberId by, Instant now) {
        PathResolution resolution = resolve(anchor, segments);
        CategoryId current = resolution.deepest();
        List<Category> created = new ArrayList<>();
        for (String segment : resolution.missing()) {
            Category folder = create(CategoryId.random(), current, segment, by, now);
            created.add(folder);
            current = folder.id();
        }
        return new CreatedPath(current, List.copyOf(created));
    }

    /** The names of the folders from the top level down to {@code id}. */
    public List<String> pathNames(CategoryId id) {
        return path(id).stream().map(Category::name).toList();
    }

    public Optional<Category> find(CategoryId id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public Category require(CategoryId id) {
        Category category = nodes.get(id);
        if (category == null) {
            throw new NotFoundException("category", id.toString());
        }
        return category;
    }

    public Collection<Category> all() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** Folders from the top level down to {@code id}. */
    public List<Category> path(CategoryId id) {
        Deque<Category> path = new ArrayDeque<>();
        Category current = require(id);
        while (current != null) {
            path.addFirst(current);
            current = current.parentId().map(nodes::get).orElse(null);
        }
        return List.copyOf(path);
    }

    /** {@code id} and all folders below it. */
    public Set<CategoryId> subtree(CategoryId id) {
        require(id);
        Set<CategoryId> result = new LinkedHashSet<>();
        Deque<CategoryId> pending = new ArrayDeque<>(List.of(id));
        while (!pending.isEmpty()) {
            CategoryId next = pending.removeFirst();
            if (result.add(next)) {
                childrenOf(next).forEach(child -> pending.addLast(child.id()));
            }
        }
        return result;
    }

    /** Folders sharing the given parent ({@code null} = top level), sorted by name. */
    public List<Category> childrenOf(CategoryId parentId) {
        return nodes.values().stream().filter(c -> Objects.equals(c.parentId().orElse(null), parentId))
                .sorted(Comparator.comparing(Category::nameKey)).toList();
    }

    /** Values inherited by a document placed in {@code id}; the nearest folder wins for the document type. */
    public Implications implications(CategoryId id, Function<LabelId, Facet> facetOf) {
        if (id == null || !nodes.containsKey(id)) {
            return Implications.NONE;
        }
        Map<LabelId, CategoryId> labels = new LinkedHashMap<>();
        Map<Subject, CategoryId> subjects = new LinkedHashMap<>();
        boolean typeTaken = false;
        List<Category> nearestFirst = new ArrayList<>(path(id));
        Collections.reverse(nearestFirst);
        for (Category category : nearestFirst) {
            for (LabelId label : category.impliedLabels()) {
                if (facetOf.apply(label) == Facet.DOCUMENT_TYPE) {
                    if (typeTaken) {
                        continue;
                    }
                    typeTaken = true;
                }
                labels.putIfAbsent(label, category.id());
            }
            category.impliedSubjects().forEach(subject -> subjects.putIfAbsent(subject, category.id()));
        }
        return new Implications(labels, subjects);
    }

    /** Derived effect rows for every folder, rebuilt after each structural or mapping change. */
    public List<CategoryEffects> effects(Function<LabelId, Facet> facetOf) {
        List<CategoryEffects> effects = new ArrayList<>();
        for (Category category : nodes.values()) {
            Implications implications = implications(category.id(), facetOf);
            Map<LabelId, Facet> labels = new LinkedHashMap<>();
            implications.labels().keySet().forEach(label -> labels.put(label, facetOf.apply(label)));
            effects.add(new CategoryEffects(category.id(), labels, implications.subjects().keySet()));
        }
        return effects;
    }

    public Changes changes() {
        List<Category> upserts = new ArrayList<>();
        // Parents first so that inserts never reference a folder that is not stored yet.
        for (Category category : nodes.values().stream().sorted(Comparator.comparingInt(c -> depth(c.id())))
                .toList()) {
            if (touched.contains(category.id())) {
                upserts.add(category);
            }
        }
        return new Changes(upserts, List.copyOf(removed));
    }

    public int depth(CategoryId id) {
        int depth = 0;
        CategoryId current = id;
        while (current != null) {
            depth++;
            if (depth > nodes.size() + 1) {
                return Integer.MAX_VALUE;
            }
            Category category = nodes.get(current);
            current = category == null ? null : category.parentId().orElse(null);
        }
        return depth;
    }

    private int height(CategoryId id) {
        int height = 1;
        for (Category child : childrenOf(id)) {
            height = Math.max(height, 1 + height(child.id()));
        }
        return height;
    }

    private Category requireVersion(CategoryId id, long expectedVersion) {
        Category category = require(id);
        if (category.version() != expectedVersion) {
            throw new StaleVersionException("category", id.toString(), expectedVersion, category.version());
        }
        return category;
    }

    private void requireUniqueSiblingName(CategoryId parentId, String name, CategoryId self) {
        String key = CatalogNames.matchKey(name);
        for (Category sibling : childrenOf(parentId)) {
            if (!sibling.id().equals(self) && sibling.nameKey().equals(key)) {
                throw new InvalidInputException("category.name.duplicate",
                        "A folder with this name already exists here",
                        Map.of("existingId", sibling.id().toString()));
            }
        }
    }
}
