package dev.aegis.documents.application;

import dev.aegis.documents.application.api.PassageQueries.State;
import dev.aegis.documents.application.ports.*;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.shared.*;
import java.time.Clock;

/** Narrow derived-data process: no member impersonation, business edits, provider calls, or text in job payloads. */
public final class PassageIndexWorker {
    public static final String PROCESS = "document-passage-index";
    public static final int BATCH = 5;
    private final PassageIndex index;
    private final DocumentRepository documents;
    private final DocumentTextExtractor extractor;
    private final Clock clock;
    public record Report(int prepared, int published, int failed) { }
    public PassageIndexWorker(PassageIndex index, DocumentRepository documents, DocumentTextExtractor extractor, Clock clock) {
        this.index = index; this.documents = documents; this.extractor = extractor; this.clock = clock;
    }
    public Report sweep(Actor actor) {
        if (actor == null || !actor.isHouseholdProcess() || !PROCESS.equals(actor.process())) { throw new NotAuthorizedException("Only the document passage index process may run this worker"); }
        index.backfill(clock.instant(), BATCH);
        int prepared = 0, published = 0, failed = 0;
        for (int count = 0; count < BATCH; count++) {
            var next = index.claim(clock.instant());
            if (next.isEmpty()) { break; }
            var claim = next.get(); prepared++;
            try {
                var document = documents.findById(claim.id()).orElseThrow(() -> new NotFoundException("document", claim.id().toString()));
                document.requireNotTrashed();
                var revision = extractor.verified(document).revision();
                if (index.publish(claim, revision, clock.instant())) { published++; }
            } catch (ArchiveUnavailableException | ArchiveRejectedException unavailable) {
                index.fail(claim, "text.sourceUnavailable", State.SOURCE_UNAVAILABLE, clock.instant()); failed++;
                break; // One unavailable archive does not receive the rest of this batch.
            } catch (DocumentTextExtractor.Refused bounded) {
                index.fail(claim, bounded.code(), State.FAILED, clock.instant()); failed++;
            } catch (NotFoundException | InvalidInputException changed) {
                index.fail(claim, "text.documentChanged", State.FAILED, clock.instant()); failed++;
            }
        }
        return new Report(prepared, published, failed);
    }
}
