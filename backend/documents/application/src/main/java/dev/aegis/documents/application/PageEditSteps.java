package dev.aegis.documents.application;

import dev.aegis.documents.application.RegisterReceivedDocument.RegistrationCommand;
import dev.aegis.documents.application.RegisterReceivedDocument.RegistrationResult;
import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.PageEdit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional steps of the page editor (ADR-0024). Each call is one short transaction; reading sources and writing
 * PDFs happen between the calls, so no transaction waits on the archive or the PDF library.
 */
public interface PageEditSteps {

    record FailureDecision(boolean retry, int attempts) {
    }

    /**
     * Stores a new operation together with its job.
     *
     * @return false when an operation with this id exists already
     */
    boolean record(PageEdit edit);

    /** The operation while it still has work to do. */
    Optional<PageEdit> pending(UUID id);

    /**
     * Registers a created document, its archival job and its derivation together. When the id is registered already,
     * only a missing derivation is added; when identical content belongs to another document, nothing is written and
     * that document is returned as a duplicate.
     */
    RegistrationResult registerOutput(RegistrationCommand command, Derivation derivation);

    FailureDecision recordFailure(UUID id, String code, boolean retryable);

    void complete(UUID id, List<PageEdit.Result> results);
}
