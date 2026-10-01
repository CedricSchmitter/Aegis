package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.adapters.rest.DocumentDto.SubjectDto;
import dev.aegis.documents.application.api.AnalysisView;
import dev.aegis.documents.application.api.FilenameView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * REST representation of the automatic analysis of a document. {@code state} is NOT_REQUESTED,
 * WAITING_FOR_ARCHIVE, PENDING, CLASSIFIED, NEEDS_REVIEW, FAILED, UNAVAILABLE, NOT_CLASSIFIED (requested for a
 * member who may not use AI) or WAITING_FOR_BUDGET (the daily AI cost limit is used up; resumes at nextAttemptAt);
 * confidence values are the model's own estimates and not calibrated probabilities.
 */
public record AnalysisDto(String state, int generation, @Schema(nullable = true) String trigger,
        @Schema(nullable = true) String requestedBy, @Schema(nullable = true) Instant requestedAt,
        @Schema(nullable = true) String runId, @Schema(nullable = true) String runState,
        @Schema(nullable = true) String failureCategory, @Schema(nullable = true) String failureCode,
        @Schema(nullable = true) Instant nextAttemptAt, @Schema(nullable = true) Instant completedAt,
        @Schema(nullable = true) String model, @Schema(nullable = true) String title,
        @Schema(nullable = true) String titleDecision, @Schema(nullable = true) LocalDate documentDate,
        @Schema(nullable = true) String documentDateEvidence, String documentDateStatus,
        @Schema(nullable = true) String shortTopic, @Schema(nullable = true) String correspondentLabelId,
        List<String> flags, List<CandidateDto> candidates, List<SubjectSuggestionDto> subjects,
        List<GeneratedDto> history, long version) {

    public record CandidateDto(String key, String facet, String name, @Schema(nullable = true) String evidence,
            @Schema(nullable = true) Double confidence, String decision, @Schema(nullable = true) String labelId) {
    }

    public record SubjectSuggestionDto(SubjectDto subject, @Schema(nullable = true) String evidence,
            @Schema(nullable = true) Double confidence, String decision) {
    }

    public record GeneratedDto(int generation, @Schema(nullable = true) String runRef,
            @Schema(nullable = true) String model, @Schema(nullable = true) Instant completedAt,
            @Schema(nullable = true) String title, @Schema(nullable = true) LocalDate documentDate,
            @Schema(nullable = true) String shortTopic) {
    }

    /** Friendly download/export name; {@code source} is GENERATED, FALLBACK or MANUAL. */
    public record FilenameDto(String filename, String source, @Schema(nullable = true) String override) {
        static FilenameDto from(FilenameView view) {
            return new FilenameDto(view.filename(), view.source(), view.override());
        }
    }

    static AnalysisDto from(AnalysisView v) {
        return new AnalysisDto(v.state(), v.generation(), v.trigger(),
                v.requestedBy() == null ? null : v.requestedBy().toString(), v.requestedAt(),
                v.runId() == null ? null : v.runId().toString(), v.runState(), v.failureCategory(), v.failureCode(),
                v.nextAttemptAt(), v.completedAt(), v.model(), v.title(), v.titleDecision(), v.documentDate(),
                v.documentDateEvidence(), v.documentDateStatus(), v.shortTopic(),
                v.correspondentLabelId() == null ? null : v.correspondentLabelId().toString(), v.flags(),
                v.candidates().stream().map(c -> new CandidateDto(c.key(), c.facet(), c.name(), c.evidence(),
                        c.confidence(), c.decision(), c.labelId() == null ? null : c.labelId().toString())).toList(),
                v.subjects().stream().map(s -> new SubjectSuggestionDto(SubjectDto.from(s.subject()), s.evidence(),
                        s.confidence(), s.decision())).toList(),
                v.history().stream().map(h -> new GeneratedDto(h.generation(), h.runRef(), h.model(),
                        h.completedAt(), h.title(), h.documentDate(), h.shortTopic())).toList(),
                v.version());
    }
}
