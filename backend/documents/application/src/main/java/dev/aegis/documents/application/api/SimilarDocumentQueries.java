package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;

/**
 * Near-duplicate hints (issue #42, ADR-0028): archived documents outside the trash whose archive texts are probably
 * the same document, such as one letter scanned twice. The hints are derived from stored fingerprints of the archive
 * text, need no AI and never change, merge or delete a document; deciding what to keep stays with the members.
 */
public interface SimilarDocumentQueries {
    /** Two documents that are probably the same; {@code first} is the one uploaded earlier. */
    record SimilarPair(DocumentId first, String firstTitle, DocumentId second, String secondTitle) {
    }

    /**
     * The documents that are probably the same as {@code id}, most similar first; empty while its text has not been
     * checked, when it has too little text to compare, and while it is in the trash.
     *
     * @throws dev.aegis.shared.NotFoundException for an unknown document
     */
    List<DocumentSummary> similarTo(Actor actor, DocumentId id);

    /**
     * Every pair of probably identical documents, oldest pair first. Also open to household processes such as the
     * review reconciler (ADR-0009).
     */
    List<SimilarPair> similarPairs(Actor actor);
}
