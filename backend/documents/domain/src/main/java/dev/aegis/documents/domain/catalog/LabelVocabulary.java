package dev.aegis.documents.domain.catalog;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The whole label vocabulary as a consistency view: resolves merge redirects and enforces that names and aliases
 * identify exactly one entry per facet, and that free-form tags never shadow an active canonical value.
 */
public final class LabelVocabulary {
    private static final int MAX_REDIRECTS = 16;

    private final Map<LabelId, Label> labels = new LinkedHashMap<>();

    public LabelVocabulary(Collection<Label> all) {
        all.forEach(label -> labels.put(label.id(), label));
    }

    public Collection<Label> all() {
        return labels.values();
    }

    public Optional<Label> find(LabelId id) {
        return Optional.ofNullable(labels.get(id));
    }

    public Label require(LabelId id) {
        Label label = labels.get(id);
        if (label == null) {
            throw new NotFoundException("label", id.toString());
        }
        return label;
    }

    /** Follows merge redirects to the entry that currently represents {@code id}. */
    public Label resolve(LabelId id) {
        Label current = require(id);
        for (int hops = 0; current.isMerged(); hops++) {
            if (hops >= MAX_REDIRECTS) {
                throw new IllegalStateException("merge redirects of label " + id + " do not terminate");
            }
            current = require(current.mergedInto().orElseThrow());
        }
        return current;
    }

    /** The non-merged entry of the facet whose name or alias equals {@code text} after normalization. */
    public Optional<Label> findByName(Facet facet, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String key = CatalogNames.matchKey(text);
        return labels.values().stream().filter(l -> l.facet() == facet && l.matchKeys().contains(key)).findFirst();
    }

    /**
     * Rejects {@code candidate} when one of its keys already identifies another entry of the same facet, or when a
     * tag and an active canonical entry (or vice versa) would share a key.
     */
    public void requireUniqueKeys(Label candidate) {
        for (String key : candidate.matchKeys()) {
            for (Label other : labels.values()) {
                if (other.id().equals(candidate.id()) || !other.matchKeys().contains(key)) {
                    continue;
                }
                if (other.facet() == candidate.facet()) {
                    throw new InvalidInputException("label.exists", "A label with this name already exists",
                            Map.of("existingId", other.id().toString(), "facet", other.facet().name()));
                }
                boolean crossesTagBoundary = other.facet().canonical() != candidate.facet().canonical();
                if (crossesTagBoundary && other.status() == LabelStatus.ACTIVE
                        && candidate.status() == LabelStatus.ACTIVE) {
                    throw new InvalidInputException("label.existsInOtherFacet",
                            "The name is already used by a label of another facet",
                            Map.of("existingId", other.id().toString(), "facet", other.facet().name()));
                }
            }
        }
    }

    /** Adds or replaces an entry in this view after a successful change. */
    public void put(Label label) {
        labels.put(label.id(), label);
    }

    public void remove(LabelId id) {
        labels.remove(id);
    }

    public Facet facetOf(LabelId id) {
        return require(id).facet();
    }

    public List<Label> ofFacet(Facet facet) {
        return labels.values().stream().filter(l -> l.facet() == facet).toList();
    }
}
