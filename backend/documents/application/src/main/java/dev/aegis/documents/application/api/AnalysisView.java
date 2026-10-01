package dev.aegis.documents.application.api;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Automatic analysis of a document, separate from archival ({@link ProcessingInfo}) and from the label chips.
 *
 * <p>{@code state}: {@code NOT_REQUESTED}, {@code WAITING_FOR_ARCHIVE}, {@code PENDING} (run queued, running or
 * waiting for a retry), {@code CLASSIFIED}, {@code NEEDS_REVIEW} (suggestions wait for a member), {@code FAILED} (run
 * failed or was cancelled; {@code failureCategory}/{@code failureCode} tell why), {@code UNAVAILABLE} (AI not
 * configured when it was requested; {@code failureCode}) or {@code NOT_CLASSIFIED} (requested on behalf of a member
 * who may not use AI, which is no failure; a member who may can start it) or {@code WAITING_FOR_BUDGET} (the
 * household's daily AI cost limit is used up; the run resumes on the next budget day at {@code nextAttemptAt}, which
 * is no failure either). Confidence values are the model's own estimates.
 * {@code version} guards member decisions on the analysis.
 */
public record AnalysisView(String state, int generation, String trigger, MemberId requestedBy, Instant requestedAt,
        UUID runId, String runState, String failureCategory, String failureCode, Instant nextAttemptAt,
        Instant completedAt, String model, String title, String titleDecision, LocalDate documentDate,
        String documentDateEvidence, String documentDateStatus, String shortTopic, UUID correspondentLabelId,
        List<String> flags, List<Candidate> candidates, List<SubjectSuggestion> subjects, List<Generated> history,
        long version) {

    public static final String NOT_REQUESTED = "NOT_REQUESTED";
    public static final String WAITING_FOR_ARCHIVE = "WAITING_FOR_ARCHIVE";
    public static final String PENDING = "PENDING";
    public static final String CLASSIFIED = "CLASSIFIED";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String FAILED = "FAILED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String NOT_CLASSIFIED = "NOT_CLASSIFIED";
    public static final String WAITING_FOR_BUDGET = "WAITING_FOR_BUDGET";

    public static final AnalysisView NONE = new AnalysisView(NOT_REQUESTED, 0, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, "UNKNOWN", null, null, List.of(), List.of(), List.of(),
            List.of(), 0);

    /** A name that is not in the vocabulary yet. {@code key} identifies it in accept/reject commands. */
    public record Candidate(String key, String facet, String name, String evidence, Double confidence,
            String decision, UUID labelId) {
    }

    public record SubjectSuggestion(SubjectView subject, String evidence, Double confidence, String decision) {
    }

    /** Values an earlier run generated. */
    public record Generated(int generation, String runRef, String model, Instant completedAt, String title,
            LocalDate documentDate, String shortTopic) {
    }
}
