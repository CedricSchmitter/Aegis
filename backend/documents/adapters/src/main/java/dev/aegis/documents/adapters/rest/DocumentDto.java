package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.ProcessingInfo;
import dev.aegis.documents.application.api.PurgeView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.TextRecognitionView;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.Trash;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * REST representation of a document; ids are strings, enum-like values keep their stable English names.
 * {@code originalFilename} is the immutable upload name, {@code filename} the friendly download/export name,
 * {@code processing} the archival state and {@code analysis} the separate automatic classification state.
 * {@code textRecognition} tells whether the archive recognized text, independent of AI. {@code archivePdfAvailable}
 * tells whether {@code GET /api/documents/{id}/archive-pdf} is known to have a searchable PDF: the archive keeps one
 * for photos and for PDFs without a text layer of their own, such as scanned pages, but not for born-digital PDFs.
 * {@code trash} is null unless the document is in the trash.
 */
public record DocumentDto(String id, String title, String titleOrigin, String originalFilename, String contentType,
        long sizeBytes, String checksumSha256, String uploadedBy, Instant uploadedAt, List<SubjectDto> subjects,
        ProcessingDto processing, boolean originalAvailable, long version, ClassificationDto classification,
        AnalysisDto.FilenameDto filename, AnalysisDto analysis, TextRecognitionDto textRecognition,
        boolean archivePdfAvailable, @Schema(nullable = true) DocumentTrashDto trash) {

    /**
     * Whether the archive recognized text (ADR-0012): {@code NOT_ARCHIVED}, {@code PENDING}, {@code UNAVAILABLE}
     * (the text could not be read yet; tried again at {@code nextAttemptAt}), {@code NONE}, {@code LITTLE} or
     * {@code RECOGNIZED}. {@code recognizedCharacters} counts letters and digits once measured.
     */
    public record TextRecognitionDto(
            @Schema(allowableValues = {"NOT_ARCHIVED", "PENDING", "UNAVAILABLE", "NONE", "LITTLE", "RECOGNIZED"})
            String state,
            @Schema(nullable = true) Integer recognizedCharacters, @Schema(nullable = true) Instant checkedAt,
            @Schema(nullable = true) Instant nextAttemptAt) {
        static TextRecognitionDto from(TextRecognitionView view) {
            return new TextRecognitionDto(view.state(), view.recognizedCharacters(), view.checkedAt(),
                    view.nextAttemptAt());
        }
    }

    /**
     * Who moved the document to the trash, when, and until when it is guaranteed to stay restorable. {@code purge} is
     * null unless a member asked to delete it permanently (ADR-0021).
     */
    public record DocumentTrashDto(String trashedBy, Instant trashedAt, Instant restorableUntil,
            @Schema(nullable = true) PurgeDto purge) {
        static DocumentTrashDto from(Trash trash, PurgeView purge) {
            return trash == null ? null : new DocumentTrashDto(trash.trashedBy().toString(), trash.trashedAt(),
                    trash.restorableUntil(), PurgeDto.from(purge));
        }
    }

    /**
     * A requested permanent deletion: {@code PENDING} while the job removes the document, {@code FAILED} when it gave
     * up with {@code failureCode}; a member can then request it again.
     */
    public record PurgeDto(@Schema(allowableValues = {"PENDING", "FAILED"}) String state, String requestedBy,
            Instant requestedAt, @Schema(nullable = true) String failureCode,
            @Schema(nullable = true) Instant failedAt) {
        static PurgeDto from(PurgeView view) {
            return view == null ? null : new PurgeDto(view.state(), view.requestedBy().toString(),
                    view.requestedAt(), view.failureCode(), view.failedAt());
        }
    }

    public record SubjectDto(String kind, @Schema(nullable = true) String memberId) {
        static SubjectDto from(SubjectView view) {
            return new SubjectDto(view.kind(), view.memberId() == null ? null : view.memberId().toString());
        }

        SubjectView toView() {
            return new SubjectView(kind, memberId == null || memberId.isBlank() ? null : MemberId.of(memberId));
        }
    }

    public record ProcessingDto(String state, int attempts, @Schema(nullable = true) String lastErrorCode,
            @Schema(nullable = true) String lastErrorMessage, @Schema(nullable = true) Instant lastErrorAt,
            boolean retryScheduled, @Schema(nullable = true) Instant nextAttemptAt,
            @Schema(nullable = true) Integer paperlessDocumentId, @Schema(nullable = true) Instant archivedAt) {
        static ProcessingDto from(ProcessingInfo info) {
            return new ProcessingDto(info.state(), info.attempts(), info.lastErrorCode(), info.lastErrorMessage(),
                    info.lastErrorAt(), info.retryScheduled(), info.nextAttemptAt(), info.paperlessDocumentId(),
                    info.archivedAt());
        }
    }

    static DocumentDto from(DocumentSummary summary) {
        return new DocumentDto(summary.id().toString(), summary.title(), summary.titleOrigin(),
                summary.originalFilename(), summary.contentType(), summary.sizeBytes(), summary.checksumSha256(),
                summary.uploadedBy().toString(), summary.uploadedAt(),
                summary.subjects().stream().map(SubjectDto::from).toList(), ProcessingDto.from(summary.processing()),
                summary.originalAvailable(), summary.version(), ClassificationDto.from(summary.classification()),
                AnalysisDto.FilenameDto.from(summary.filename()), AnalysisDto.from(summary.analysis()),
                TextRecognitionDto.from(summary.textRecognition()), summary.archivePdfAvailable(),
                DocumentTrashDto.from(summary.trash(), summary.purge()));
    }
}
