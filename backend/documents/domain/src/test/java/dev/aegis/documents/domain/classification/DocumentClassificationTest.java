package dev.aegis.documents.domain.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentClassificationTest {
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final MemberId LISA = MemberId.random();
    private static final LabelRef INVOICE = new LabelRef(LabelId.random(), Facet.DOCUMENT_TYPE);
    private static final LabelRef CONTRACT = new LabelRef(LabelId.random(), Facet.DOCUMENT_TYPE);
    private static final LabelRef TELEKOM = new LabelRef(LabelId.random(), Facet.ORGANIZATION);
    private static final LabelRef INTERNET = new LabelRef(LabelId.random(), Facet.TOPIC);
    private static final LabelRef PHONE = new LabelRef(LabelId.random(), Facet.TOPIC);

    private final DocumentClassification classification = DocumentClassification.unsorted(DocumentId.random());

    private static Proposal applied(LabelRef ref) {
        return new Proposal(ref, new Confidence(0.93), true);
    }

    private static Proposal suggested(LabelRef ref) {
        return new Proposal(ref, new Confidence(0.41), false);
    }

    @Test
    void newDocumentsStartUnsortedAndPlacementIsSingle() {
        assertThat(classification.isPristine()).isTrue();
        CategoryId internet = CategoryId.random();
        CategoryId water = CategoryId.random();

        assertThat(classification.placeIn(internet, LISA, NOW)).isTrue();
        assertThat(classification.placeIn(internet, LISA, NOW)).isFalse();
        classification.placeIn(water, LISA, NOW);

        assertThat(classification.categoryId()).contains(water);
        assertThat(classification.updatedBy()).contains(LISA);
        classification.placeIn(null, LISA, NOW);
        assertThat(classification.categoryId()).isEmpty();
    }

    @Test
    void manualLabelsSupportSeveralTopicsButOnlyOneDocumentType() {
        classification.setLabels(List.of(INVOICE, TELEKOM, INTERNET, PHONE), LISA, NOW);

        assertThat(classification.effectiveLabelIds()).containsExactly(INVOICE.id(), TELEKOM.id(), INTERNET.id(),
                PHONE.id());
        assertThat(classification.assignments()).allSatisfy(a -> {
            assertThat(a.origin()).isEqualTo(AssignmentOrigin.MANUAL);
            assertThat(a.state()).isEqualTo(ReviewState.CONFIRMED);
            assertThat(a.decidedBy()).isEqualTo(LISA);
        });
        assertThatThrownBy(() -> classification.setLabels(List.of(INVOICE, CONTRACT), LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "classification.singleType");

        classification.setLabels(List.of(CONTRACT, TELEKOM), LISA, NOW);
        assertThat(classification.effectiveLabelIds()).containsExactly(TELEKOM.id(), CONTRACT.id());
        assertThat(classification.assignment(INVOICE.id())).isEmpty();
        assertThat(classification.effectiveType()).contains(CONTRACT.id());
    }

    @Test
    void automaticProposalsNeverOverrideMemberDecisions() {
        classification.setLabels(List.of(CONTRACT), LISA, NOW);

        DocumentClassification.ProposalResult result = classification.propose(List.of(applied(INVOICE),
                applied(CONTRACT), applied(TELEKOM), suggested(INTERNET)), 0, "run:1", false, NOW);

        assertThat(result).isEqualTo(new DocumentClassification.ProposalResult(1, 2, 1));
        assertThat(classification.effectiveType()).contains(CONTRACT.id());
        assertThat(classification.assignment(INVOICE.id())).get().extracting(LabelAssignment::state)
                .isEqualTo(ReviewState.SUGGESTED);
        assertThat(classification.assignment(TELEKOM.id())).get().satisfies(a -> {
            assertThat(a.state()).isEqualTo(ReviewState.APPLIED);
            assertThat(a.origin()).isEqualTo(AssignmentOrigin.AUTOMATIC);
            assertThat(a.confidence()).isEqualTo(new Confidence(0.93));
            assertThat(a.sourceRef()).isEqualTo("run:1");
        });
        assertThat(classification.effectiveLabelIds()).containsExactlyInAnyOrder(CONTRACT.id(), TELEKOM.id());
    }

    @Test
    void removedAutomaticValuesStayRejectedAcrossLaterRuns() {
        classification.propose(List.of(applied(TELEKOM), suggested(INTERNET)), 0, "run:1", false, NOW);
        classification.reject(INTERNET.id(), LISA, NOW);
        classification.setLabels(List.of(), LISA, NOW);

        assertThat(classification.assignment(TELEKOM.id())).get().extracting(LabelAssignment::state)
                .isEqualTo(ReviewState.REJECTED);
        assertThat(classification.effectiveLabelIds()).isEmpty();

        DocumentClassification.ProposalResult retry = classification.propose(List.of(applied(TELEKOM),
                applied(INTERNET)), 0, "run:2", false, NOW);

        assertThat(retry.skipped()).isEqualTo(2);
        assertThat(classification.effectiveLabelIds()).isEmpty();
    }

    @Test
    void membersCanStillChooseAPreviouslyRejectedValue() {
        classification.propose(List.of(suggested(TELEKOM)), 0, "run:1", false, NOW);
        classification.reject(TELEKOM.id(), LISA, NOW);

        classification.setLabels(List.of(TELEKOM), LISA, NOW);

        assertThat(classification.assignment(TELEKOM.id())).get().satisfies(a -> {
            assertThat(a.origin()).isEqualTo(AssignmentOrigin.MANUAL);
            assertThat(a.state()).isEqualTo(ReviewState.CONFIRMED);
        });
    }

    @Test
    void staleAnalysesAreRefused() {
        DocumentClassification stored = DocumentClassification.rehydrate(DocumentId.random(), null, List.of(), LISA,
                NOW, 4);

        assertThatThrownBy(() -> stored.propose(List.of(applied(TELEKOM)), 3, "run:old", false, NOW))
                .isInstanceOf(StaleVersionException.class);
        assertThat(stored.assignments()).isEmpty();
    }

    @Test
    void aFolderTypeTurnsAutomaticTypeProposalsIntoSuggestions() {
        classification.propose(List.of(applied(INVOICE), applied(INTERNET)), 0, "run:1", true, NOW);

        assertThat(classification.assignment(INVOICE.id())).get().extracting(LabelAssignment::state)
                .isEqualTo(ReviewState.SUGGESTED);
        assertThat(classification.assignment(INTERNET.id())).get().extracting(LabelAssignment::state)
                .isEqualTo(ReviewState.APPLIED);
    }

    @Test
    void acceptingATypeSuggestionReplacesTheCurrentTypeAndSettlesOtherTypeSuggestions() {
        classification.propose(List.of(applied(INVOICE), suggested(CONTRACT)), 0, "run:1", false, NOW);

        classification.accept(CONTRACT.id(), LISA, NOW);

        assertThat(classification.effectiveType()).contains(CONTRACT.id());
        assertThat(classification.assignment(CONTRACT.id())).get().satisfies(a -> {
            assertThat(a.state()).isEqualTo(ReviewState.CONFIRMED);
            assertThat(a.origin()).isEqualTo(AssignmentOrigin.AUTOMATIC);
            assertThat(a.decidedBy()).isEqualTo(LISA);
        });
        assertThat(classification.assignment(INVOICE.id())).get().extracting(LabelAssignment::state)
                .isEqualTo(ReviewState.REJECTED);
        assertThatThrownBy(() -> classification.accept(TELEKOM.id(), LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "classification.suggestion.missing");
    }

    @Test
    void choosingATypeManuallyRejectsPendingTypeSuggestions() {
        classification.propose(List.of(suggested(INVOICE), suggested(INTERNET)), 0, "run:1", false, NOW);

        classification.setLabels(List.of(CONTRACT), LISA, NOW);

        assertThat(classification.assignment(INVOICE.id())).get().extracting(LabelAssignment::state)
                .isEqualTo(ReviewState.REJECTED);
        assertThat(classification.assignment(INTERNET.id())).get().extracting(LabelAssignment::state)
                .isEqualTo(ReviewState.SUGGESTED);
    }

    @Test
    void keptFolderLabelsRespectAnOwnDocumentType() {
        classification.setLabels(List.of(CONTRACT), LISA, NOW);

        assertThat(classification.keep(INVOICE, LISA, NOW)).isFalse();
        assertThat(classification.keep(INTERNET, LISA, NOW)).isTrue();
        assertThat(classification.keep(INTERNET, LISA, NOW)).isFalse();
        assertThat(classification.effectiveLabelIds()).containsExactly(CONTRACT.id(), INTERNET.id());
    }

    @Test
    void mergingKeepsTheStrongerDecision() {
        LabelRef target = new LabelRef(LabelId.random(), Facet.ORGANIZATION);
        classification.propose(List.of(suggested(target)), 0, "run:1", false, NOW);
        classification.reject(target.id(), LISA, NOW);
        classification.setLabels(List.of(TELEKOM), LISA, NOW);

        assertThat(classification.replaceLabel(TELEKOM.id(), target.id())).isTrue();

        assertThat(classification.assignment(TELEKOM.id())).isEmpty();
        assertThat(classification.assignment(target.id())).get().satisfies(a -> {
            assertThat(a.state()).isEqualTo(ReviewState.CONFIRMED);
            assertThat(a.labelId()).isEqualTo(target.id());
        });
        assertThat(classification.replaceLabel(TELEKOM.id(), target.id())).isFalse();
    }

    @Test
    void restoreOnlyAppliesToUnclassifiedDocuments() {
        CategoryId folder = CategoryId.random();
        LabelAssignment exported = new LabelAssignment(TELEKOM.id(), Facet.ORGANIZATION, AssignmentOrigin.AUTOMATIC,
                ReviewState.APPLIED, new Confidence(0.8), "run:7", null, NOW);

        classification.restore(folder, List.of(exported), LISA, NOW);

        assertThat(classification.categoryId()).contains(folder);
        assertThat(classification.assignments()).containsExactly(exported);
        assertThatThrownBy(() -> classification.restore(null, List.of(), LISA, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manualAssignmentsAreAlwaysConfirmedAndConfidenceIsBounded() {
        assertThatThrownBy(() -> new LabelAssignment(TELEKOM.id(), Facet.ORGANIZATION, AssignmentOrigin.MANUAL,
                ReviewState.SUGGESTED, null, null, LISA, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Confidence(1.2)).hasFieldOrPropertyWithValue("code",
                "classification.confidence.invalid");
        assertThatThrownBy(() -> new Confidence(Double.NaN)).hasFieldOrPropertyWithValue("code",
                "classification.confidence.invalid");
    }

    @Test
    void tagConversionUpdatesStoredFacets() {
        LabelRef tag = new LabelRef(LabelId.random(), Facet.TAG);
        classification.setLabels(List.of(tag), LISA, NOW);

        classification.refacet(tag.id(), Facet.TOPIC);

        assertThat(classification.assignment(tag.id())).get().extracting(LabelAssignment::facet)
                .isEqualTo(Facet.TOPIC);
    }
}
