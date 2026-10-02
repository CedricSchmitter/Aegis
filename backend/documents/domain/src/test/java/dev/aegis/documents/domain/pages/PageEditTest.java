package dev.aegis.documents.domain.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PageEditTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private final MemberId cedric = MemberId.random();
    private final DocumentId source = DocumentId.random();
    private final PageEditPlan plan = new PageEditPlan(List.of(
            new PageEditOutput("First", false, null, Set.of(), List.of(PageRef.ofDocument(source, 0, 0))),
            new PageEditOutput("Second", false, null, Set.of(), List.of(PageRef.ofDocument(source, 1, 0)))));

    private PageEdit requested() {
        return PageEdit.requested(UUID.fromString("5b0a3e8e-6f3c-4f57-9d0c-1f2a3b4c5d6e"), cedric, NOW, plan, null);
    }

    @Test
    void resultIdsAreStablePerOperationAndPosition() {
        PageEdit edit = requested();
        PageEdit again = requested();
        PageEdit other = PageEdit.requested(UUID.randomUUID(), cedric, NOW, plan, null);

        assertThat(edit.resultIdFor(0)).isEqualTo(again.resultIdFor(0));
        assertThat(edit.resultIdFor(0)).isNotEqualTo(edit.resultIdFor(1));
        assertThat(edit.resultIdFor(0)).isNotEqualTo(other.resultIdFor(0));
        assertThatThrownBy(() -> edit.resultIdFor(2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRepeatedRequestMustCarryTheSamePlan() {
        PageEdit edit = requested();
        PageEditPlan changed = new PageEditPlan(List.of(plan.outputs().get(0)));
        assertThat(edit.sameRequest(plan)).isTrue();
        assertThat(edit.sameRequest(changed)).isFalse();
    }

    @Test
    void aPlanWithNewPagesNeedsTheStoredScan() {
        PageEditPlan scanned = new PageEditPlan(List.of(new PageEditOutput("Scan", false, null, Set.of(),
                List.of(PageRef.ofScan(0, 0)))));
        assertThatThrownBy(() -> PageEdit.requested(UUID.randomUUID(), cedric, NOW, scanned, null))
                .isInstanceOf(IllegalArgumentException.class);
        PageEdit edit = PageEdit.requested(UUID.randomUUID(), cedric, NOW, scanned,
                new PageEdit.Scan("page-edits/x.pdf", Checksum.of("a".repeat(64)), 1));
        assertThat(edit.scan()).isPresent();
    }

    @Test
    void retryableFailuresKeepTheOperationRequestedUntilTheAttemptsAreUsedUp() {
        PageEdit edit = requested();
        assertThat(edit.recordFailure("paperless.unavailable", true, 3, NOW)).isTrue();
        assertThat(edit.recordFailure("paperless.unavailable", true, 3, NOW)).isTrue();
        assertThat(edit.isPending()).isTrue();
        assertThat(edit.recordFailure("paperless.unavailable", true, 3, NOW)).isFalse();
        assertThat(edit.state()).isEqualTo(PageEdit.State.FAILED);
        assertThat(edit.failureCode()).contains("paperless.unavailable");
        assertThat(edit.finishedAt()).contains(NOW);
        assertThatThrownBy(() -> edit.recordFailure("x", true, 3, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRefusalFailsAtOnceAndCompletionNeedsEveryOutput() {
        PageEdit refused = requested();
        assertThat(refused.recordFailure("pageEdit.source.encrypted", false, 5, NOW)).isFalse();
        assertThat(refused.state()).isEqualTo(PageEdit.State.FAILED);

        PageEdit edit = requested();
        edit.recordFailure("paperless.unavailable", true, 5, NOW);
        List<PageEdit.Result> one = List.of(new PageEdit.Result(0, edit.resultIdFor(0), false));
        assertThatThrownBy(() -> edit.complete(one, NOW)).isInstanceOf(IllegalArgumentException.class);
        edit.complete(List.of(one.get(0), new PageEdit.Result(1, edit.resultIdFor(1), false)), NOW);
        assertThat(edit.state()).isEqualTo(PageEdit.State.COMPLETED);
        assertThat(edit.failureCode()).isEmpty();
        assertThat(edit.results()).hasSize(2);
    }
}
