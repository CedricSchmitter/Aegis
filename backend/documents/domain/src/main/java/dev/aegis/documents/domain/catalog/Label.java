package dev.aegis.documents.domain.catalog;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Vocabulary entry of one facet. Built-in entries carry a language-independent key that the UI translates; a custom
 * name, once set, is shown instead. Aliases let equal entities be found under several spellings. Uniqueness across
 * entries is checked by {@link LabelVocabulary}.
 */
public final class Label {
    public static final int MAX_ALIASES = 50;

    private final LabelId id;
    private Facet facet;
    private final String builtinKey;
    private String name;
    private final List<String> aliases;
    private LabelStatus status;
    private LabelId mergedInto;
    private final MemberId createdBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Label(LabelId id, Facet facet, String builtinKey, String name, List<String> aliases, LabelStatus status,
            LabelId mergedInto, MemberId createdBy, Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.facet = Objects.requireNonNull(facet);
        this.builtinKey = builtinKey;
        this.name = name;
        this.aliases = new ArrayList<>(aliases);
        this.status = Objects.requireNonNull(status);
        this.mergedInto = mergedInto;
        this.createdBy = createdBy;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.version = version;
    }

    /** A member-defined entry; the name is kept in the language and spelling it was entered in. */
    public static Label create(LabelId id, Facet facet, String name, MemberId createdBy, Instant now) {
        return new Label(id, facet, null, CatalogNames.requireName(name, "label"), List.of(), LabelStatus.ACTIVE,
                null, Objects.requireNonNull(createdBy), now, now, 0);
    }

    /** Restores an entry from persistence or an export; performs structural validation only. */
    public static Label rehydrate(LabelId id, Facet facet, String builtinKey, String name, List<String> aliases,
            LabelStatus status, LabelId mergedInto, MemberId createdBy, Instant createdAt, Instant updatedAt,
            long version) {
        if (builtinKey == null && name == null) {
            throw new IllegalArgumentException("label " + id + " needs a name or a built-in key");
        }
        if ((status == LabelStatus.MERGED) != (mergedInto != null)) {
            throw new IllegalArgumentException("label " + id + " merge redirect does not match its status");
        }
        return new Label(id, facet, builtinKey, name, aliases, status, mergedInto, createdBy, createdAt, updatedAt,
                version);
    }

    public void rename(String newName, Instant now) {
        requireNotMerged();
        this.name = CatalogNames.requireName(newName, "label");
        aliases.removeIf(alias -> CatalogNames.matchKey(alias).equals(CatalogNames.matchKey(this.name)));
        this.updatedAt = now;
    }

    /** Replaces all aliases. Duplicates and aliases equal to the name are dropped. */
    public void replaceAliases(List<String> newAliases, Instant now) {
        requireNotMerged();
        List<String> cleaned = cleanAliases(newAliases == null ? List.of() : newAliases);
        if (cleaned.size() > MAX_ALIASES) {
            throw new InvalidInputException("label.alias.tooMany", "At most " + MAX_ALIASES + " aliases");
        }
        aliases.clear();
        aliases.addAll(cleaned);
        this.updatedAt = now;
    }

    public void retire(Instant now) {
        requireNotMerged();
        if (status != LabelStatus.RETIRED) {
            status = LabelStatus.RETIRED;
            updatedAt = now;
        }
    }

    public void reactivate(Instant now) {
        requireNotMerged();
        if (status != LabelStatus.ACTIVE) {
            status = LabelStatus.ACTIVE;
            updatedAt = now;
        }
    }

    /**
     * Folds this entry into {@code target}: the target takes over this entry's name and aliases as aliases, and this
     * entry remains only as a redirect. Moving assignments and folder mappings is the caller's job.
     */
    public void mergeInto(Label target, Instant now) {
        requireNotMerged();
        if (target.id.equals(id)) {
            throw new InvalidInputException("label.merge.self", "A label cannot be merged into itself");
        }
        if (target.facet != facet) {
            throw new InvalidInputException("label.merge.facet", "Labels of different facets cannot be merged");
        }
        if (target.status != LabelStatus.ACTIVE) {
            throw new InvalidInputException("label.merge.targetInactive", "Merge target must be active");
        }
        List<String> absorbed = new ArrayList<>(target.aliases);
        if (name != null) {
            absorbed.add(name);
        }
        absorbed.addAll(aliases);
        target.aliases.clear();
        target.aliases.addAll(target.cleanAliases(absorbed));
        target.updatedAt = now;
        aliases.clear();
        status = LabelStatus.MERGED;
        mergedInto = target.id;
        updatedAt = now;
    }

    /** Turns a free-form tag into a canonical multi-valued facet, keeping identity and assignments. */
    public void convertTo(Facet newFacet, Instant now) {
        requireNotMerged();
        if (facet != Facet.TAG || newFacet == null || newFacet.singleValued() || !newFacet.canonical()) {
            throw new InvalidInputException("label.convert.unsupported",
                    "Only tags can be converted, and only into organizations or topics");
        }
        facet = newFacet;
        updatedAt = now;
    }

    /** Normalized keys of the name and all aliases; a merged entry has none. */
    public Set<String> matchKeys() {
        Set<String> keys = new LinkedHashSet<>();
        if (status == LabelStatus.MERGED) {
            return keys;
        }
        if (name != null) {
            keys.add(CatalogNames.matchKey(name));
        }
        aliases.forEach(alias -> keys.add(CatalogNames.matchKey(alias)));
        return keys;
    }

    public boolean acceptsAssignments() {
        return status == LabelStatus.ACTIVE;
    }

    public boolean isBuiltin() {
        return builtinKey != null;
    }

    public boolean isMerged() {
        return status == LabelStatus.MERGED;
    }

    public LabelId id() {
        return id;
    }

    public Facet facet() {
        return facet;
    }

    public Optional<String> builtinKey() {
        return Optional.ofNullable(builtinKey);
    }

    /** Custom name; empty for a built-in entry that still uses its translated product name. */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public List<String> aliases() {
        return Collections.unmodifiableList(aliases);
    }

    public LabelStatus status() {
        return status;
    }

    public Optional<LabelId> mergedInto() {
        return Optional.ofNullable(mergedInto);
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

    private void requireNotMerged() {
        if (status == LabelStatus.MERGED) {
            throw new InvalidInputException("label.merged", "Label " + id + " was merged into " + mergedInto);
        }
    }

    private List<String> cleanAliases(List<String> candidates) {
        Set<String> seen = new LinkedHashSet<>();
        if (name != null) {
            seen.add(CatalogNames.matchKey(name));
        }
        List<String> cleaned = new ArrayList<>();
        for (String candidate : candidates) {
            String alias = CatalogNames.requireName(candidate, "label.alias");
            if (seen.add(CatalogNames.matchKey(alias))) {
                cleaned.add(alias);
            }
        }
        return cleaned;
    }
}
