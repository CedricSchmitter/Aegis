package dev.aegis.portability.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.documents.application.api.AnalysisRecord.SubjectEntry;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Restoring an export before schema 9 maps "both" the way the startup migration does (ADR-0017). */
class LegacySubjectsTest {
    private static final Instant AT = Instant.parse("2026-09-20T08:00:00Z");
    private final MemberId lisa = MemberId.random();
    private final MemberId cedric = MemberId.random();
    private final MemberId decider = MemberId.random();

    @Test
    void bothBecomesEachPersonOnceNextToTheExistingValues() {
        List<SubjectView> restored = LegacySubjects.expand(List.of(new SubjectView("HOUSE", null),
                new SubjectView("MEMBER", cedric), new SubjectView("HOUSEHOLD", null)), List.of(lisa, cedric));

        assertThat(restored).containsExactly(new SubjectView("HOUSE", null), new SubjectView("MEMBER", cedric),
                new SubjectView("MEMBER", lisa));
    }

    @Test
    void valuesWithoutBothStayAsTheyAre() {
        List<SubjectView> subjects = List.of(new SubjectView("MEMBER", lisa));

        assertThat(LegacySubjects.expand(subjects, List.of(lisa, cedric))).isEqualTo(subjects);
    }

    @Test
    void suggestionsKeepTheirDecisionAndDoNotRepeatAPerson() {
        SubjectEntry accepted = new SubjectEntry(new SubjectView("HOUSEHOLD", null), 0.8, "Mieter: beide", "ACCEPTED",
                decider, AT);
        SubjectEntry explicit = new SubjectEntry(new SubjectView("MEMBER", cedric), 0.6, "Cedric", "REJECTED",
                decider, AT);

        List<SubjectEntry> restored = LegacySubjects.expandSuggestions(List.of(accepted, explicit),
                List.of(lisa, cedric), List.of());

        assertThat(restored).containsExactly(
                new SubjectEntry(new SubjectView("MEMBER", lisa), 0.8, "Mieter: beide", "ACCEPTED", decider, AT),
                explicit);
    }

    @Test
    void aPendingSuggestionIsNotAddedForAPersonTheDocumentIsAlreadyAbout() {
        SubjectEntry pending = new SubjectEntry(new SubjectView("HOUSEHOLD", null), 0.7, "Familie", "SUGGESTED",
                null, null);

        List<SubjectEntry> restored = LegacySubjects.expandSuggestions(List.of(pending), List.of(lisa, cedric),
                List.of(new SubjectView("MEMBER", lisa)));

        assertThat(restored).extracting(SubjectEntry::subject).containsExactly(new SubjectView("MEMBER", cedric));
        assertThat(LegacySubjects.expandSuggestions(null, List.of(lisa), List.of())).isNull();
    }
}
