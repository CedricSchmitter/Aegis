package dev.aegis.documents.domain.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.bulk.BulkAnalysis.ItemState;
import dev.aegis.documents.domain.bulk.BulkAnalysis.Outcome;
import dev.aegis.documents.domain.bulk.BulkAnalysis.State;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BulkAnalysisTest {
    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private final MemberId lisa = MemberId.random();
    private final List<DocumentId> documents = IntStream.range(0, 5).mapToObj(i -> DocumentId.random()).toList();

    private BulkAnalysis started(int batchSize) {
        return BulkAnalysis.start(UUID.randomUUID(), lisa, NOW, BulkScope.all(), documents, batchSize);
    }

    @Test
    void anEmptyRunCannotStartAndTheBatchIsBounded() {
        assertThatThrownBy(() -> BulkAnalysis.start(UUID.randomUUID(), lisa, NOW, BulkScope.all(), List.of(), 2))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.code()).isEqualTo("analysis.bulk.empty"));
        assertThatThrownBy(() -> started(BulkAnalysis.MAX_BATCH + 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkScope(BulkScope.Kind.FOLDER, null, true))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void documentsAreRequestedInOrderInBoundedBatchesAndOnlyOnce() {
        BulkAnalysis bulk = started(2);
        assertThat(bulk.nextToRequest()).containsExactly(documents.get(0), documents.get(1));
        bulk.requested(documents.get(0), 1, NOW);
        bulk.requested(documents.get(1), 3, NOW);
        assertThat(bulk.nextToRequest()).isEmpty();
        assertThatThrownBy(() -> bulk.requested(documents.get(0), 2, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bulk.requested(documents.get(2), 1, NOW)).isInstanceOf(IllegalStateException.class);

        bulk.finished(documents.get(0), Outcome.CLASSIFIED, null, NOW);
        assertThat(bulk.nextToRequest()).containsExactly(documents.get(2));
        assertThatThrownBy(() -> bulk.finished(documents.get(0), Outcome.FAILED, "x", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bulk.inFlight()).singleElement().satisfies(item -> {
            assertThat(item.documentId()).isEqualTo(documents.get(1));
            assertThat(item.generation()).isEqualTo(3);
        });
        assertThat(bulk.settle(NOW)).isFalse();
    }

    @Test
    void theRunCompletesWhenEveryDocumentWasRequestedOrSkippedAndEveryRunEnded() {
        BulkAnalysis bulk = started(10);
        for (DocumentId document : documents.subList(0, 4)) {
            bulk.requested(document, 1, NOW);
        }
        bulk.skipped(documents.get(4), "document.trashed", NOW);
        bulk.finished(documents.get(0), Outcome.CLASSIFIED, null, NOW);
        bulk.finished(documents.get(1), Outcome.FAILED, "provider.unavailable", NOW);
        bulk.finished(documents.get(2), Outcome.SUPERSEDED, null, NOW);
        assertThat(bulk.settle(NOW)).isFalse();
        bulk.finished(documents.get(3), Outcome.CLASSIFIED, null, NOW);

        assertThat(bulk.settle(NOW)).isTrue();
        assertThat(bulk.state()).isEqualTo(State.COMPLETED);
        assertThat(bulk.finishedAt()).contains(NOW);
        BulkAnalysis.Progress progress = bulk.progress();
        assertThat(progress.total()).isEqualTo(5);
        assertThat(progress.count(Outcome.CLASSIFIED)).isEqualTo(2);
        assertThat(progress.count(Outcome.FAILED)).isEqualTo(1);
        assertThat(progress.count(ItemState.SKIPPED)).isEqualTo(1);
        assertThat(bulk.changedItems()).hasSize(5);
    }

    @Test
    void aStopRequestsNothingMoreButFollowsTheRunsInFlight() {
        BulkAnalysis bulk = started(2);
        bulk.requested(documents.get(0), 1, NOW);
        bulk.stop("ai.notConfigured");
        assertThat(bulk.stopping()).isTrue();
        assertThat(bulk.nextToRequest()).isEmpty();
        assertThat(bulk.settle(NOW)).isFalse();

        bulk.finished(documents.get(0), Outcome.CLASSIFIED, null, NOW);
        assertThat(bulk.settle(NOW)).isTrue();
        assertThat(bulk.state()).isEqualTo(State.STOPPED);
        assertThat(bulk.stopCode()).contains("ai.notConfigured");
        assertThat(bulk.progress().count(ItemState.PENDING)).isEqualTo(4);
    }

    @Test
    void cancellingHandsBackTheInFlightItemsAndEndsTheRunAtOnce() {
        BulkAnalysis bulk = started(2);
        bulk.requested(documents.get(0), 1, NOW);
        bulk.requested(documents.get(1), 1, NOW);
        bulk.finished(documents.get(0), Outcome.CLASSIFIED, null, NOW);
        bulk.requested(documents.get(2), 2, NOW);

        List<BulkAnalysis.Item> inFlight = bulk.cancel(lisa, NOW);
        assertThat(inFlight).extracting(BulkAnalysis.Item::documentId)
                .containsExactly(documents.get(1), documents.get(2));
        assertThat(bulk.state()).isEqualTo(State.CANCELLED);
        assertThat(bulk.cancelledBy()).contains(lisa);
        assertThat(bulk.nextToRequest()).isEmpty();
        assertThat(bulk.progress().count(ItemState.CANCELLED)).isEqualTo(4);
        assertThat(bulk.progress().count(Outcome.CLASSIFIED)).isEqualTo(1);
        assertThat(bulk.cancel(lisa, NOW)).as("a second cancellation changes nothing").isEmpty();
        assertThat(bulk.settle(NOW)).isFalse();
    }
}
