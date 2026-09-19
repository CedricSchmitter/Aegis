package dev.aegis.documents.application.ports;

import dev.aegis.documents.application.api.OriginalContent;
import java.nio.file.Path;
import java.util.List;

/**
 * Outbound port to the document archive (Paperless). Transport failures surface as
 * {@link ArchiveUnavailableException}; definitive rejections as {@link ArchiveRejectedException}.
 */
public interface PaperlessArchive {

    sealed interface SubmissionOutcome permits Accepted, Rejected {
    }

    record Accepted(String taskId) implements SubmissionOutcome {
    }

    record Rejected(String code, String message) implements SubmissionOutcome {
    }

    sealed interface TaskOutcome permits Pending, Completed, Duplicate, Failed {
    }

    record Pending() implements TaskOutcome {
    }

    record Completed(int paperlessDocumentId) implements TaskOutcome {
    }

    /** The archive already holds identical content; the existing id is authoritative. */
    record Duplicate(int existingPaperlessDocumentId) implements TaskOutcome {
    }

    record Failed(String code, String message) implements TaskOutcome {
    }

    record ArchiveProbe(boolean reachable, String serverVersion, String detail) {
    }

    SubmissionOutcome submit(Path file, String title, String originalFilename);

    TaskOutcome checkTask(String taskId);

    OriginalContent fetchOriginal(int paperlessDocumentId);

    /** OCR text of an archived document as extracted by the archive; empty when it found no text. */
    String fetchContent(int paperlessDocumentId);

    boolean exists(int paperlessDocumentId);

    /** Paperless ids matching the query in title or content. */
    List<Integer> searchContent(String query);

    ArchiveProbe probe();
}
