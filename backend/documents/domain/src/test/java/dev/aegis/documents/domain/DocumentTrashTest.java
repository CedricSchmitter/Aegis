package dev.aegis.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The trash keeps a document whole: only member changes wait for the restore, processing continues. */
class DocumentTrashTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final MemberId LISA = MemberId.random();

    private static Document received() {
        return Document.received(DocumentId.random(), "Mietvertrag", "mietvertrag.pdf", "application/pdf", 1234,
                new Checksum("b".repeat(64)), MemberId.random(), NOW, Set.of(Subject.house()), "ingress/x.pdf");
    }

    @Test
    void trashingRecordsWhoWhenAndTheRecoveryPromiseAndRepeatingChangesNothing() {
        Document document = received();

        assertThat(document.moveToTrash(LISA, NOW, Trash.RecoveryWindow.DEFAULT)).isTrue();
        assertThat(document.moveToTrash(MemberId.random(), NOW.plusSeconds(60), Trash.RecoveryWindow.DEFAULT))
                .isFalse();

        assertThat(document.isTrashed()).isTrue();
        assertThat(document.trash()).contains(new Trash(LISA, NOW, NOW.plus(Duration.ofDays(30))));
        assertThat(document.title()).isEqualTo("Mietvertrag");
        assertThat(document.subjects()).containsExactly(Subject.house());
    }

    @Test
    void memberChangesAreRefusedUntilTheDocumentIsRestored() {
        Document document = received();
        document.moveToTrash(LISA, NOW, Trash.RecoveryWindow.DEFAULT);

        assertThatThrownBy(() -> document.rename("Neu")).isInstanceOf(TrashedException.class)
                .hasFieldOrPropertyWithValue("code", "document.trashed");
        assertThatThrownBy(() -> document.changeSubjects(Set.of(Subject.house())))
                .isInstanceOf(TrashedException.class);

        assertThat(document.restoreFromTrash()).isTrue();
        assertThat(document.restoreFromTrash()).isFalse();
        document.rename("Mietvertrag 2026");
        assertThat(document.title()).isEqualTo("Mietvertrag 2026");
        assertThat(document.trash()).isEmpty();
    }

    @Test
    void archivalOfATrashedUploadStillCompletesSoTheOriginalIsSafe() {
        Document document = received();
        document.moveToTrash(LISA, NOW, Trash.RecoveryWindow.DEFAULT);

        document.markSubmitted("task-1", NOW.plusSeconds(1));
        document.markArchived(7, NOW.plusSeconds(30));

        assertThat(document.state()).isEqualTo(ProcessingState.ARCHIVED);
        assertThat(document.paperlessDocumentId()).contains(7);
        assertThat(document.isTrashed()).isTrue();
    }

    @Test
    void aTrashRecordCarriedOverByARestoreKeepsItsOriginalPromise() {
        Document document = received();
        Trash exported = new Trash(LISA, NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(20)));

        document.moveToTrash(exported);

        assertThat(document.trash()).contains(exported);
    }

    @Test
    void theRecoveryWindowIsAtLeastOneDay() {
        assertThatThrownBy(() -> new Trash.RecoveryWindow(Duration.ofHours(23)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Trash(LISA, NOW, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
