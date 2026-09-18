package dev.aegis.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentTest {
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final Checksum CHECKSUM = new Checksum("a".repeat(64));

    private static Document received() {
        return Document.received(DocumentId.random(), "Contract", "contract.pdf", "application/pdf", 1234, CHECKSUM,
                MemberId.random(), NOW, Set.of(Subject.house()), "ingress/x.pdf");
    }

    @Test
    void receiptStartsInReceivedStateWithStagingCopy() {
        Document document = received();

        assertThat(document.state()).isEqualTo(ProcessingState.RECEIVED);
        assertThat(document.isStagingPresent()).isTrue();
        assertThat(document.attempts()).isZero();
        assertThatThrownBy(() -> Document.received(DocumentId.random(), "Empty", "e.pdf", "application/pdf", 0,
                CHECKSUM, MemberId.random(), NOW, Set.of(), "p")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void submissionThenArchivalIsIdempotentForTheSamePaperlessId() {
        Document document = received();
        document.markSubmitted("task-1", NOW);
        assertThat(document.state()).isEqualTo(ProcessingState.SUBMITTED);

        document.markArchived(42, NOW.plusSeconds(30));
        document.markArchived(42, NOW.plusSeconds(60));

        assertThat(document.state()).isEqualTo(ProcessingState.ARCHIVED);
        assertThat(document.paperlessDocumentId()).contains(42);
        assertThat(document.archivedAt()).contains(NOW.plusSeconds(30));
        assertThatThrownBy(() -> document.markArchived(43, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retryableFailuresStayRetryableUntilTheAttemptBudgetIsExhausted() {
        Document document = received();
        ProcessingFailure failure = new ProcessingFailure("paperless.unavailable", "connection refused", NOW);

        assertThat(document.recordFailure(failure, true, 3)).isTrue();
        assertThat(document.recordFailure(failure, true, 3)).isTrue();
        assertThat(document.state()).isEqualTo(ProcessingState.RECEIVED);
        assertThat(document.recordFailure(failure, true, 3)).isFalse();
        assertThat(document.state()).isEqualTo(ProcessingState.FAILED);
        assertThat(document.lastFailure()).contains(failure);
    }

    @Test
    void nonRetryableFailureFailsImmediatelyAndManualRetryResetsBudget() {
        Document document = received();
        document.markSubmitted("task-1", NOW);

        assertThat(document.recordFailure(new ProcessingFailure("paperless.rejected", "bad file", NOW), false, 5))
                .isFalse();
        assertThat(document.state()).isEqualTo(ProcessingState.FAILED);
        assertThat(document.paperlessTaskId()).contains("task-1");

        document.requestRetry();

        assertThat(document.state()).isEqualTo(ProcessingState.RECEIVED);
        assertThat(document.attempts()).isZero();
        assertThat(document.paperlessTaskId()).isEmpty();
        assertThat(document.lastFailure()).isPresent();
        assertThatThrownBy(document::requestRetry).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void failedSubmissionReturnsToReceivedSoTheFileIsResubmitted() {
        Document document = received();
        document.markSubmitted("task-1", NOW);

        document.recordFailure(new ProcessingFailure("paperless.timeout", "no result", NOW), true, 5);

        assertThat(document.state()).isEqualTo(ProcessingState.RECEIVED);
        assertThat(document.paperlessTaskId()).isEmpty();
    }

    @Test
    void stagingCopyCanOnlyBeRemovedAfterArchival() {
        Document document = received();

        assertThatThrownBy(document::markStagingRemoved).isInstanceOf(IllegalStateException.class);
        document.markArchived(7, NOW);
        document.markStagingRemoved();

        assertThat(document.isStagingPresent()).isFalse();
    }

    @Test
    void subjectsValidateMemberAssociation() {
        assertThatThrownBy(() -> new Subject(SubjectKind.MEMBER, null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new Subject(SubjectKind.HOUSE, MemberId.random()))
                .isInstanceOf(InvalidInputException.class);
        assertThat(Subject.member(MemberId.random()).memberId()).isPresent();
    }

    @Test
    void generatedTitleReplacesOnlyTheUntouchedFilenameTitleAndOnlyOnce() {
        Document document = received();
        assertThat(document.titleOrigin()).isEqualTo(TitleOrigin.FILENAME);

        assertThat(document.applyGeneratedTitle("Telekom Rechnung", "stale title")).isFalse();
        assertThat(document.applyGeneratedTitle("Telekom Rechnung", "Contract")).isTrue();
        assertThat(document.title()).isEqualTo("Telekom Rechnung");
        assertThat(document.titleOrigin()).isEqualTo(TitleOrigin.AUTOMATIC);
        assertThat(document.applyGeneratedTitle("Telekom Rechnung September", "Telekom Rechnung")).isFalse();
    }

    @Test
    void aMembersTitleIsNeverReplacedAutomatically() {
        Document document = received();
        document.rename("Unser Internetvertrag");

        assertThat(document.titleOrigin()).isEqualTo(TitleOrigin.MANUAL);
        assertThat(document.applyGeneratedTitle("Telekom Rechnung", "Unser Internetvertrag")).isFalse();
        assertThat(document.title()).isEqualTo("Unser Internetvertrag");
    }
}
