package dev.aegis.portability.application;

import dev.aegis.documents.application.api.AnalysisRecord;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.shared.MemberId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Exports before schema 9 may carry the retired subject "both" ({@code HOUSEHOLD}, ADR-0017). A restore maps it the
 * way the startup migration does: one member subject per person of the restoring household, in its place, next to
 * the values the record already names. The assistant account is never one of the persons.
 */
final class LegacySubjects {
    static final String HOUSEHOLD = "HOUSEHOLD";
    private static final String MEMBER = "MEMBER";
    private static final String SUGGESTED = "SUGGESTED";

    private LegacySubjects() {
    }

    /** Subjects of a document or folder, with exported member ids already mapped to local ones. */
    static List<SubjectView> expand(List<SubjectView> subjects, List<MemberId> persons) {
        Set<SubjectView> result = new LinkedHashSet<>();
        for (SubjectView subject : subjects) {
            if (HOUSEHOLD.equals(subject.kind())) {
                persons.forEach(person -> result.add(new SubjectView(MEMBER, person)));
            } else {
                result.add(subject);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Subject suggestions of an exported analysis. A suggestion that already names a person wins over one derived
     * from "both", and a pending one is not added for a person the document is already about.
     */
    static List<AnalysisRecord.SubjectEntry> expandSuggestions(List<AnalysisRecord.SubjectEntry> entries,
            List<MemberId> persons, Collection<SubjectView> documentSubjects) {
        if (entries == null) {
            return null;
        }
        Set<MemberId> suggested = new LinkedHashSet<>();
        entries.stream().filter(entry -> MEMBER.equals(entry.subject().kind()) && entry.subject().memberId() != null)
                .forEach(entry -> suggested.add(entry.subject().memberId()));
        List<AnalysisRecord.SubjectEntry> result = new ArrayList<>();
        for (AnalysisRecord.SubjectEntry entry : entries) {
            if (!HOUSEHOLD.equals(entry.subject().kind())) {
                result.add(entry);
                continue;
            }
            boolean pending = SUGGESTED.equals(entry.decision());
            for (MemberId person : persons) {
                SubjectView subject = new SubjectView(MEMBER, person);
                if (suggested.contains(person) || pending && documentSubjects.contains(subject)) {
                    continue;
                }
                result.add(new AnalysisRecord.SubjectEntry(subject, entry.confidence(), entry.evidence(),
                        entry.decision(), entry.decidedBy(), entry.decidedAt()));
                suggested.add(person);
            }
        }
        return result;
    }
}
