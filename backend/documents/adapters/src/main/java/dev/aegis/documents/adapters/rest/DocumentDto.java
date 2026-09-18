package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.ProcessingInfo;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.shared.MemberId;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * REST representation of a document; ids are strings, enum-like values keep their stable English names.
 * {@code originalFilename} is the immutable upload name, {@code filename} the friendly download/export name,
 * {@code processing} the archival state and {@code analysis} the separate automatic classification state.
 */
public record DocumentDto(String id, String title, String titleOrigin, String originalFilename, String contentType,
        long sizeBytes, String checksumSha256, String uploadedBy, Instant uploadedAt, List<SubjectDto> subjects,
        ProcessingDto processing, boolean originalAvailable, long version, ClassificationDto classification,
        AnalysisDto.FilenameDto filename, AnalysisDto analysis) {

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
                AnalysisDto.FilenameDto.from(summary.filename()), AnalysisDto.from(summary.analysis()));
    }
}
