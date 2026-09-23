package dev.aegis.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A trashed task keeps its data, leaves Today and waits for a restore before a member may change it. */
class TaskTrashTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();

    @Test
    void aTrashedTaskIsNoOpenWorkAndLeavesToday() {
        Task task = Task.create("Zählerstand melden", "Keller", anna, TODAY, TODAY, new TaskSource(DocumentId.random()),
                ben, NOW);

        assertThat(task.moveToTrash(ben, NOW, Trash.RecoveryWindow.DEFAULT)).isTrue();
        assertThat(task.moveToTrash(anna, NOW.plusSeconds(5), Trash.RecoveryWindow.DEFAULT)).isFalse();

        assertThat(task.isOpen()).isFalse();
        assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(task.isDueOn(TODAY)).isFalse();
        assertThat(TodayPolicy.build(List.of(task), TODAY).dueToday()).isEmpty();
        assertThat(task.trash()).get().extracting(Trash::trashedBy).isEqualTo(ben);
        assertThat(task.owner()).contains(anna);
        assertThat(task.note()).isEqualTo("Keller");
    }

    @Test
    void memberChangesAreRefusedWhileTrashed() {
        Task task = Task.create("Zahlung", "", anna, TODAY, null, null, anna, NOW);
        task.moveToTrash(anna, NOW, Trash.RecoveryWindow.DEFAULT);

        assertThatThrownBy(() -> task.complete(anna, NOW)).isInstanceOf(TrashedException.class)
                .hasFieldOrPropertyWithValue("code", "task.trashed");
        assertThatThrownBy(() -> task.assign(ben, NOW)).isInstanceOf(TrashedException.class);
        assertThatThrownBy(() -> task.edit("Neu", "", NOW)).isInstanceOf(TrashedException.class);
        assertThatThrownBy(() -> task.planWork(TODAY, NOW)).isInstanceOf(TrashedException.class);
        assertThatThrownBy(() -> task.changeDueDate(TODAY, NOW)).isInstanceOf(TrashedException.class);
    }

    @Test
    void restoringKeepsTheOwnerOnlyWhileTheyAreStillAMember() {
        Task kept = Task.create("Kept", "", anna, TODAY, null, null, anna, NOW);
        Task orphaned = Task.create("Orphaned", "", ben, TODAY, null, null, anna, NOW);
        kept.moveToTrash(anna, NOW, Trash.RecoveryWindow.DEFAULT);
        orphaned.moveToTrash(anna, NOW, Trash.RecoveryWindow.DEFAULT);

        assertThat(kept.restoreFromTrash(true, NOW.plusSeconds(1))).isTrue();
        assertThat(orphaned.restoreFromTrash(false, NOW.plusSeconds(1))).isTrue();
        assertThat(kept.restoreFromTrash(true, NOW.plusSeconds(2))).isFalse();

        assertThat(kept.owner()).contains(anna);
        assertThat(orphaned.owner()).isEmpty();
        assertThat(orphaned.isOpen()).isTrue();
        assertThat(orphaned.isUnassigned()).isTrue();
    }

    @Test
    void aSupersededAutomaticOccurrenceRevivesButACompletedOneStaysDone() {
        CancellationReview review = new CancellationReview(ContractId.random(), "Fitnessstudio",
                LocalDate.of(2027, 12, 31), LocalDate.of(2027, 9, 30), LocalDate.of(2027, 9, 23), 3);
        Task superseded = Task.createCancellationReview(TaskId.random(), "Fitnessstudio", review.cancellationDeadline(),
                null, review, anna, NOW);
        superseded.assign(ben, NOW);
        superseded.cancelAutomatic(NOW);
        Task done = Task.createCancellationReview(TaskId.random(), "Fitnessstudio", review.cancellationDeadline(),
                null, review, anna, NOW);
        done.complete(anna, NOW);
        Task manual = Task.create("Manuell", "", anna, TODAY, null, null, anna, NOW);

        assertThat(superseded.reviveAutomatic(NOW.plusSeconds(1))).isTrue();
        assertThat(done.reviveAutomatic(NOW.plusSeconds(1))).isFalse();
        assertThat(manual.reviveAutomatic(NOW.plusSeconds(1))).isFalse();

        assertThat(superseded.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(superseded.owner()).contains(ben);
        assertThat(done.status()).isEqualTo(TaskStatus.DONE);
    }
}
