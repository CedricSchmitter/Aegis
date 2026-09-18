package dev.aegis.documents.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.analysis.AnalysisTypes.DateStatus;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Decision;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Status;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Trigger;
import dev.aegis.documents.domain.analysis.AnalysisValues.DocumentDate;
import dev.aegis.documents.domain.analysis.AnalysisValues.LabelCandidate;
import dev.aegis.documents.domain.analysis.AnalysisValues.Outcome;
import dev.aegis.documents.domain.analysis.AnalysisValues.SubjectSuggestion;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentAnalysisTest {
    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private final DocumentId id = DocumentId.random();
    private final MemberId lisa = MemberId.random();

    private static Outcome outcome(int generation, String title, Decision titleDecision, List<LabelCandidate> names,
            List<SubjectSuggestion> subjects) {
        return new Outcome(generation, "ai-run:" + generation, "claude-sonnet-5", NOW, title, titleDecision,
                "Scan 0001", new DocumentDate(LocalDate.of(2026, 9, 17), "17.09.2026", DateStatus.FOUND), "Internet",
                null, Set.of(), names, subjects, 2, 1);
    }

    private static LabelCandidate name(String value) {
        return new LabelCandidate(Facet.ORGANIZATION, value, value, 0.9, Decision.SUGGESTED, null, null, null);
    }

    @Test
    void uploadsWaitForTheArchiveAndEveryRequestStartsANewGeneration() {
        DocumentAnalysis analysis = DocumentAnalysis.awaitingArchive(id, lisa, NOW);
        assertThat(analysis.awaitsArchive()).isTrue();

        int first = analysis.request(lisa, Trigger.UPLOAD, NOW);
        int second = analysis.request(lisa, Trigger.MANUAL, NOW);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(analysis.accepts(1)).isFalse();
        assertThat(analysis.accepts(2)).isTrue();
    }

    @Test
    void aLateAnswerOfAnOlderGenerationIsRefused() {
        DocumentAnalysis analysis = DocumentAnalysis.none(id);
        analysis.request(lisa, Trigger.MANUAL, NOW);
        analysis.request(lisa, Trigger.MANUAL, NOW);

        assertThatThrownBy(() -> analysis.complete(outcome(1, "Alt", Decision.SUGGESTED, List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class);
        analysis.complete(outcome(2, "Neu", Decision.SUGGESTED, List.of(), List.of()));
        assertThat(analysis.status()).isEqualTo(Status.COMPLETED);
        assertThat(analysis.accepts(2)).isFalse();
    }

    @Test
    void rejectedTitlesNamesAndSubjectsAreNotProposedAgain() {
        DocumentAnalysis analysis = DocumentAnalysis.none(id);
        Subject house = Subject.house();
        analysis.request(lisa, Trigger.MANUAL, NOW);
        analysis.complete(outcome(1, "Telekom Rechnung", Decision.SUGGESTED, List.of(name("Telekom GmbH")),
                List.of(new SubjectSuggestion(house, 0.8, "Hausanschluss", Decision.SUGGESTED, null, null))));
        analysis.decideTitle(false, lisa);
        String key = analysis.candidates().getFirst().matchKey();
        analysis.decideCandidate(key, false, null, lisa, NOW);
        analysis.decideSubject(house, false, lisa, NOW);

        analysis.request(lisa, Trigger.MANUAL, NOW);
        analysis.complete(outcome(2, "Telekom Rechnung", Decision.SUGGESTED, List.of(name("telekom  gmbh")),
                List.of(new SubjectSuggestion(house, 0.9, "Hausanschluss", Decision.SUGGESTED, null, null))));

        assertThat(analysis.titleDecision()).contains(Decision.REJECTED);
        assertThat(analysis.candidates()).singleElement().extracting(LabelCandidate::decision)
                .isEqualTo(Decision.REJECTED);
        assertThat(analysis.subjects()).singleElement().extracting(SubjectSuggestion::decision)
                .isEqualTo(Decision.REJECTED);
        assertThat(analysis.hasPendingSuggestions()).isFalse();
    }

    @Test
    void theMembersFilenameSurvivesEveryRunAndHistoryIsBounded() {
        DocumentAnalysis analysis = DocumentAnalysis.none(id);
        analysis.overrideFilename("Mein-Vertrag", lisa, NOW);
        for (int generation = 1; generation <= DocumentAnalysis.MAX_HISTORY + 3; generation++) {
            analysis.request(lisa, Trigger.MANUAL, NOW);
            analysis.complete(outcome(generation, "Titel " + generation, Decision.SUGGESTED, List.of(), List.of()));
        }

        assertThat(analysis.filenameOverride()).contains("Mein-Vertrag");
        assertThat(analysis.snapshot().history()).hasSize(DocumentAnalysis.MAX_HISTORY);
        assertThat(analysis.snapshot().history().getFirst().title()).isEqualTo("Titel "
                + (DocumentAnalysis.MAX_HISTORY + 2));
    }

    @Test
    void decisionsNeedAPendingSuggestion() {
        DocumentAnalysis analysis = DocumentAnalysis.none(id);

        assertThatThrownBy(() -> analysis.decideTitle(true, lisa)).isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "analysis.title.noSuggestion");
        assertThatThrownBy(() -> analysis.decideCandidate("ORGANIZATION:x", true, new LabelId(java.util.UUID
                .randomUUID()), lisa, NOW)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void restoreOnlyFillsAnUntouchedAnalysisAndNeverRestoresARunningRequest() {
        DocumentAnalysis source = DocumentAnalysis.none(id);
        source.request(lisa, Trigger.MANUAL, NOW);
        source.complete(outcome(1, "Titel", Decision.APPLIED, List.of(), List.of()));
        DocumentAnalysis.Snapshot exported = source.snapshot();

        DocumentAnalysis fresh = DocumentAnalysis.none(id);
        fresh.restore(exported);
        assertThat(fresh.status()).isEqualTo(Status.COMPLETED);
        assertThat(fresh.documentDate().usable()).isEqualTo(LocalDate.of(2026, 9, 17));

        DocumentAnalysis pending = DocumentAnalysis.none(id);
        DocumentAnalysis requested = DocumentAnalysis.none(id);
        requested.request(lisa, Trigger.MANUAL, NOW);
        pending.restore(requested.snapshot());
        assertThat(pending.status()).isEqualTo(Status.NOT_REQUESTED);
        assertThatThrownBy(() -> fresh.restore(exported)).isInstanceOf(IllegalStateException.class);
    }
}
