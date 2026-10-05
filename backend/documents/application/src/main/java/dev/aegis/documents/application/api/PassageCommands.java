package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;

/** Restart-safe requests; extraction is performed by the bounded persistent worker. */
public interface PassageCommands {
    void retry(Actor actor, DocumentId documentId);
    void rebuild(Actor actor);
}
