package dev.aegis.documents.domain.pages;

import java.util.Optional;
import java.util.UUID;

public interface PageEditRepository {

    Optional<PageEdit> findById(UUID id);

    /**
     * Stores a new operation.
     *
     * @return false when an operation with this id exists already, for example from a concurrent repeated request
     */
    boolean insert(PageEdit edit);

    /** Saves a change of a stored operation; a concurrent change raises a stale version error. */
    void save(PageEdit edit);
}
