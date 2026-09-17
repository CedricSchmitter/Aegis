package dev.aegis.documents.domain.catalog;

import java.util.Optional;

/** Persistence of the label vocabulary with optimistic version checks and unique match keys per facet. */
public interface LabelRepository {
    /** Whole vocabulary including retired and merged entries. */
    LabelVocabulary load();

    /** Whole vocabulary with exclusive vocabulary access for the rest of the transaction. */
    LabelVocabulary loadForUpdate();

    Optional<Label> findById(LabelId id);

    void save(Label label);

    /** Removes an unused entry; callers must have verified that nothing references it. */
    void delete(LabelId id);
}
