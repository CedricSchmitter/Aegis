package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import java.util.List;
import java.util.UUID;

/** Vocabulary maintenance. Renaming, merging, retiring or deleting a label never deletes a document. */
public interface LabelCommands {

    record MergeResult(LabelView target, int documentsUpdated, int foldersUpdated) {
    }

    /** {@code id} may be chosen by the client for idempotent retries; {@code facet} is a stable facet name. */
    LabelView create(Actor actor, UUID id, String facet, String name);

    LabelView rename(Actor actor, UUID id, String name, long expectedVersion);

    LabelView replaceAliases(Actor actor, UUID id, List<String> aliases, long expectedVersion);

    LabelView retire(Actor actor, UUID id, long expectedVersion);

    LabelView reactivate(Actor actor, UUID id, long expectedVersion);

    /** Moves assignments and folder mappings of {@code sourceId} to {@code targetId}; the source becomes a redirect. */
    MergeResult merge(Actor actor, UUID sourceId, UUID targetId, long expectedVersion);

    /** Turns a tag into an organization or topic, keeping its identity and assignments. */
    LabelView convert(Actor actor, UUID id, String facet, long expectedVersion);

    /** Deletes a label that no document, folder or redirect uses; built-in labels can only be retired. */
    void delete(Actor actor, UUID id, long expectedVersion);
}
