package dev.aegis.documents.application.ports;

import dev.aegis.documents.application.api.OriginalContent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    /**
     * What the archive extracted from one of its documents: the OCR text ({@code ""} when it found none) and whether
     * it keeps a searchable archive version besides the original (ADR-0016).
     */
    record ArchivedText(String content, boolean archiveVersion) {
    }

    /** Hands the original over for consumption; {@code contentType} is the media type Aegis verified. */
    SubmissionOutcome submit(Path file, String title, String originalFilename, String contentType);

    TaskOutcome checkTask(String taskId);

    OriginalContent fetchOriginal(int paperlessDocumentId);

    /**
     * The searchable PDF the archive generated from the original (its archive version), or empty when it keeps
     * none, as for a born-digital PDF. It is derived data of the archive: it never replaces the original and is not
     * exported, because the archive can generate it again.
     */
    Optional<byte[]> fetchArchivePdf(int paperlessDocumentId);

    /** OCR text of an archived document as extracted by the archive; empty when it found no text. */
    String fetchContent(int paperlessDocumentId);

    /** The OCR text together with whether the archive keeps an archive version, read in one request. */
    ArchivedText fetchArchivedText(int paperlessDocumentId);

    boolean exists(int paperlessDocumentId);

    /** Paperless ids matching the query in title or content. */
    List<Integer> searchContent(String query);

    ArchiveProbe probe();
}
