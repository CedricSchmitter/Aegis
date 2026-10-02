package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The page editor (ADR-0024): creates new documents from pages of stored documents and newly scanned pages. Sources
 * are never changed. The request is acknowledged once the plan and the scanned pages are stored durably; a background
 * job creates the documents.
 */
public interface PageEditCommands {

    /** A page of a stored document, or of the scanned pages when {@code document} is null; zero-based index. */
    record PageRequest(DocumentId document, int pageIndex, int rotation) {
    }

    record OutputRequest(String title, boolean titleChosen, UUID categoryId, List<SubjectView> subjects,
            List<PageRequest> pages) {
    }

    /**
     * {@code id} is chosen by the client when the plan is started; repeating the request with the same id and plan
     * returns the existing operation. {@code scan} is the PDF of newly scanned pages, or null.
     */
    record EditRequest(UUID id, List<OutputRequest> outputs, InputStream scan, long declaredScanSize) {
    }

    /** {@code existing}: identical content was stored already, and {@code document} is that document. */
    record ResultView(int output, DocumentId document, String title, boolean existing, boolean trashed) {
    }

    /**
     * {@code state} is {@code REQUESTED}, {@code COMPLETED} or {@code FAILED} with {@code failureCode}. While the
     * operation runs, and after it failed, {@code results} lists the documents created so far.
     */
    record PageEditView(UUID id, String state, String failureCode, MemberId requestedBy, Instant requestedAt,
            Instant finishedAt, List<ResultView> results) {
    }

    PageEditView request(Actor actor, EditRequest request);

    /** @throws dev.aegis.shared.NotFoundException with resource {@code pageEdit} for an unknown id */
    PageEditView get(Actor actor, UUID id);
}
