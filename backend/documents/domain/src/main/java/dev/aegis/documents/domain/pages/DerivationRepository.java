package dev.aegis.documents.domain.pages;

import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.Optional;

/** Persistence of the source relationships of documents created by the page editor. */
public interface DerivationRepository {

    /** Stores the derivation unless the document already has one; repeating the call changes nothing. */
    void insertIfAbsent(Derivation derivation);

    Optional<Derivation> findByDocument(DocumentId document);

    /** Derivations of documents that took pages from {@code source}, oldest first. */
    List<Derivation> findBySource(DocumentId source);
}
