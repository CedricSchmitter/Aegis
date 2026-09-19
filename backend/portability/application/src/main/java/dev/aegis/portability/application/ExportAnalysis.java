package dev.aegis.portability.application;

import dev.aegis.documents.application.api.AnalysisRecord;
import dev.aegis.documents.application.api.AnalysisView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.portability.application.ExportFormat.AnalysisEntry;
import dev.aegis.portability.application.ExportFormat.CandidateEntry;
import dev.aegis.portability.application.ExportFormat.GeneratedEntry;
import dev.aegis.portability.application.ExportFormat.SubjectEntry;
import dev.aegis.portability.application.ExportFormat.SubjectSuggestionEntry;
import dev.aegis.shared.MemberId;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit mapping of the document analysis between the documents contract and the export file. Member and label ids
 * are remapped on restore; ids that do not exist locally are dropped instead of failing the restore.
 */
final class ExportAnalysis {
    private ExportAnalysis() {
    }

    static AnalysisEntry toEntry(AnalysisRecord r) {
        if (r == null) {
            return null;
        }
        return new AnalysisEntry(r.status(), r.generation(), r.trigger(), text(r.requestedBy()), r.requestedAt(),
                r.completedAt(), r.runRef(), r.model(), r.appliedLabels(), r.suggestedLabels(), r.title(),
                r.titleDecision(), r.titleBefore(), text(r.documentDate()), r.documentDateEvidence(),
                r.documentDateStatus(), r.shortTopic(), text(r.correspondentLabelId()), r.flags(),
                r.candidates().stream().map(c -> new CandidateEntry(c.facet(), c.name(), c.evidence(),
                        c.confidence(), c.decision(), text(c.labelId()), text(c.decidedBy()), c.decidedAt())).toList(),
                r.subjects().stream().map(s -> new SubjectSuggestionEntry(new SubjectEntry(s.subject().kind(),
                        text(s.subject().memberId())), s.confidence(), s.evidence(), s.decision(),
                        text(s.decidedBy()), s.decidedAt())).toList(),
                r.filenameOverride(), text(r.filenameBy()), r.filenameAt(),
                r.history().stream().map(h -> new GeneratedEntry(h.generation(), h.runRef(), h.model(),
                        h.completedAt(), h.title(), text(h.documentDate()), h.shortTopic())).toList());
    }

    static AnalysisRecord toRecord(AnalysisEntry e, Map<String, String> members, Map<UUID, UUID> labels) {
        return new AnalysisRecord(e.status(), e.generation(), e.trigger(), member(e.requestedBy(), members),
                e.requestedAt(), e.completedAt(), e.runRef(), e.model(), e.appliedLabels(), e.suggestedLabels(),
                e.title(), e.titleDecision(), e.titleBefore(), date(e.documentDate()), e.documentDateEvidence(),
                e.documentDateStatus(), e.shortTopic(), label(e.correspondentLabelId(), labels),
                e.flags(),
                e.candidates() == null ? null : e.candidates().stream().map(c -> new AnalysisRecord.Candidate(
                        c.facet(), c.name(), c.evidence(), c.confidence(), c.decision(), label(c.labelId(), labels),
                        member(c.decidedBy(), members), c.decidedAt())).toList(),
                e.subjects() == null ? null : e.subjects().stream()
                        .filter(s -> s.subject() != null && (s.subject().memberId() == null
                                || members.containsKey(s.subject().memberId())))
                        .map(s -> new AnalysisRecord.SubjectEntry(new SubjectView(s.subject().kind(),
                                member(s.subject().memberId(), members)), s.confidence(), s.evidence(), s.decision(),
                                member(s.decidedBy(), members), s.decidedAt())).toList(),
                e.filenameOverride(), member(e.filenameBy(), members), e.filenameAt(),
                e.history() == null ? null : e.history().stream().map(h -> new AnalysisView.Generated(
                        h.generation(), h.runRef(), h.model(), h.completedAt(), h.title(), date(h.documentDate()),
                        h.shortTopic())).toList());
    }

    private static MemberId member(String exported, Map<String, String> members) {
        String local = exported == null ? null : members.get(exported);
        return local == null ? null : MemberId.of(local);
    }

    private static UUID label(String exported, Map<UUID, UUID> labels) {
        return exported == null ? null : labels.get(UUID.fromString(exported));
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
