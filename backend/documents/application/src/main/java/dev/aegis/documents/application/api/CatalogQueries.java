package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import java.util.List;
import java.util.Optional;

/** Read access to the shared folder tree and label vocabulary. */
public interface CatalogQueries {
    CategoryListing categories(Actor actor);

    /** Every label including retired and merged entries, with usage counts. */
    List<LabelView> labels(Actor actor);

    /** The non-merged label of {@code facet} whose name or alias equals {@code name} after normalization. */
    Optional<LabelView> resolveLabel(Actor actor, String facet, String name);
}
