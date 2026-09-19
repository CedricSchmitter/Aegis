package dev.aegis.documents.application.api;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Portable form of a document's automatic analysis for export and restore: generated values with provenance, member
 * decisions, the filename override and the history of earlier generated values. Run state is not portable; a
 * restored analysis is either completed or not requested.
 */
public record AnalysisRecord(String status, int generation, String trigger, MemberId requestedBy,
        Instant requestedAt, Instant completedAt, String runRef, String model, int appliedLabels, int suggestedLabels,
        String title, String titleDecision, String titleBefore, LocalDate documentDate, String documentDateEvidence,
        String documentDateStatus, String shortTopic, UUID correspondentLabelId, List<String> flags,
        List<Candidate> candidates, List<SubjectEntry> subjects, String filenameOverride, MemberId filenameBy,
        Instant filenameAt, List<AnalysisView.Generated> history) {

    public AnalysisRecord {
        flags = flags == null ? List.of() : List.copyOf(flags);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public record Candidate(String facet, String name, String evidence, Double confidence, String decision,
            UUID labelId, MemberId decidedBy, Instant decidedAt) {
    }

    public record SubjectEntry(SubjectView subject, Double confidence, String evidence, String decision,
            MemberId decidedBy, Instant decidedAt) {
    }
}
