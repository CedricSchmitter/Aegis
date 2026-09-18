package dev.aegis.documents.application;

import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.shared.StaleVersionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test double; PostgreSQL semantics are covered by the integration tests. */
public class InMemoryLabelRepository implements LabelRepository {
    private final Map<LabelId, Label> store = new LinkedHashMap<>();

    /** Models migration seeds, which start at version zero instead of going through a user write. */
    void seed(Label label) {
        store.put(label.id(), copy(label));
    }

    @Override
    public LabelVocabulary load() {
        return new LabelVocabulary(store.values().stream().map(InMemoryLabelRepository::copy).toList());
    }

    @Override
    public Optional<Label> findById(LabelId id) {
        return Optional.ofNullable(store.get(id)).map(InMemoryLabelRepository::copy);
    }

    @Override
    public void save(Label label) {
        Label stored = store.get(label.id());
        if (stored != null && stored.version() != label.version()) {
            throw new StaleVersionException("label", label.id().toString(), label.version(), stored.version());
        }
        store.put(label.id(), bump(label));
    }

    @Override
    public void delete(LabelId id) {
        store.remove(id);
    }

    private static Label copy(Label label) {
        return rehydrate(label, label.version());
    }

    private static Label bump(Label label) {
        return rehydrate(label, label.version() + 1);
    }

    private static Label rehydrate(Label label, long version) {
        return Label.rehydrate(label.id(), label.facet(), label.builtinKey().orElse(null),
                label.name().orElse(null), List.copyOf(label.aliases()), label.status(),
                label.mergedInto().orElse(null), label.createdBy().orElse(null), label.createdAt(),
                label.updatedAt(), version);
    }
}
