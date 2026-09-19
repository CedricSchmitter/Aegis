package dev.aegis.documents.application;

import dev.aegis.documents.application.api.AnalysisRecord;
import dev.aegis.documents.application.api.AnalysisView;
import dev.aegis.documents.domain.analysis.AnalysisTypes.DateStatus;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Decision;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Flag;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Status;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Trigger;
import dev.aegis.documents.domain.analysis.AnalysisValues.DocumentDate;
import dev.aegis.documents.domain.analysis.AnalysisValues.GeneratedValues;
import dev.aegis.documents.domain.analysis.AnalysisValues.LabelCandidate;
import dev.aegis.documents.domain.analysis.AnalysisValues.SubjectSuggestion;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Explicit mapping between the analysis aggregate and its portable export form. */
final class AnalysisRecords {
    private AnalysisRecords() {
    }

    static AnalysisRecord toRecord(DocumentAnalysis analysis) {
        DocumentAnalysis.Snapshot s = analysis.snapshot();
        return new AnalysisRecord(s.status().name(), s.generation(), s.trigger() == null ? null : s.trigger().name(),
                s.requestedBy(), s.requestedAt(), s.completedAt(), s.completedRunRef(), s.model(), s.appliedLabels(),
                s.suggestedLabels(), s.title(), s.titleDecision() == null ? null : s.titleDecision().name(),
                s.titleBefore(), s.documentDate().value(), s.documentDate().evidence(),
                s.documentDate().status().name(), s.shortTopic(),
                s.correspondent() == null ? null : s.correspondent().value(),
                s.flags().stream().map(Enum::name).sorted().toList(),
                s.candidates().stream().map(c -> new AnalysisRecord.Candidate(c.facet().name(), c.name(),
                        c.evidence(), c.confidence(), c.decision().name(),
                        c.labelId() == null ? null : c.labelId().value(), c.decidedBy(), c.decidedAt())).toList(),
                s.subjects().stream().map(x -> new AnalysisRecord.SubjectEntry(
                        Subjects.toViews(List.of(x.subject())).getFirst(), x.confidence(), x.evidence(),
                        x.decision().name(), x.decidedBy(), x.decidedAt())).toList(),
                s.filenameOverride(), s.filenameBy(), s.filenameAt(),
                s.history().stream().map(h -> new AnalysisView.Generated(h.generation(), h.runRef(), h.model(),
                        h.completedAt(), h.title(), h.documentDate(), h.shortTopic())).toList());
    }

    /** Invalid or unknown values are dropped rather than failing a restore. */
    static DocumentAnalysis.Snapshot toSnapshot(AnalysisRecord r) {
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        r.flags().forEach(flag -> parse(Flag.class, flag).ifPresent(flags::add));
        DateStatus dateStatus = parse(DateStatus.class, r.documentDateStatus()).orElse(DateStatus.UNKNOWN);
        DocumentDate date = dateStatus == DateStatus.FOUND && r.documentDate() == null ? DocumentDate.UNKNOWN
                : new DocumentDate(r.documentDate(), r.documentDateEvidence(), dateStatus);
        List<LabelCandidate> candidates = r.candidates().stream()
                .filter(c -> parse(Facet.class, c.facet()).isPresent() && c.name() != null)
                .map(c -> new LabelCandidate(Facet.valueOf(c.facet()), c.name(), c.evidence(), c.confidence(),
                        parse(Decision.class, c.decision()).orElse(Decision.SUGGESTED),
                        c.labelId() == null ? null : new LabelId(c.labelId()), c.decidedBy(), c.decidedAt()))
                .toList();
        List<SubjectSuggestion> subjects = r.subjects().stream().flatMap(x -> {
            try {
                return java.util.stream.Stream.of(new SubjectSuggestion(
                        Subjects.fromViews(List.of(x.subject())).iterator().next(), x.confidence(), x.evidence(),
                        parse(Decision.class, x.decision()).orElse(Decision.SUGGESTED), x.decidedBy(),
                        x.decidedAt()));
            } catch (RuntimeException invalid) {
                return java.util.stream.Stream.empty();
            }
        }).toList();
        List<GeneratedValues> history = r.history().stream().map(h -> new GeneratedValues(h.generation(),
                h.runRef(), h.model(), h.completedAt(), h.title(), h.documentDate(), h.shortTopic())).toList();
        return new DocumentAnalysis.Snapshot(parse(Status.class, r.status()).orElse(Status.NOT_REQUESTED),
                Math.max(0, r.generation()), parse(Trigger.class, r.trigger()).orElse(null), r.requestedBy(),
                r.requestedAt(), null, r.completedAt(), r.runRef(), r.model(), r.appliedLabels(),
                r.suggestedLabels(), r.title(), parse(Decision.class, r.titleDecision()).orElse(null),
                r.titleBefore(), date, r.shortTopic(),
                r.correspondentLabelId() == null ? null : new LabelId(r.correspondentLabelId()), flags, candidates,
                subjects, r.filenameOverride(), r.filenameBy(), r.filenameAt(), history);
    }

    private static <E extends Enum<E>> java.util.Optional<E> parse(Class<E> type, String value) {
        if (value == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Enum.valueOf(type, value));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }
}
