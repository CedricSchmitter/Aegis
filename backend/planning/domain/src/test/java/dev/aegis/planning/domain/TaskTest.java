package dev.aegis.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TaskTest {
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final LocalDate DUE = LocalDate.of(2026, 9, 30);
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();

    @Test
    void planningWorkNeverMovesTheDueDate() {
        Task task = Task.create("Kündigung prüfen", "", anna, DUE, LocalDate.of(2026, 9, 20), null, anna, NOW);

        task.planWork(LocalDate.of(2026, 10, 5), NOW.plusSeconds(1));

        assertThat(task.plannedDate()).contains(LocalDate.of(2026, 10, 5));
        assertThat(task.dueDate()).contains(DUE);
    }

    @Test
    void dueDateChangeIsAnExplicitSeparateDecision() {
        Task task = Task.create("Zahlung", "", null, DUE, null, null, anna, NOW);

        task.changeDueDate(LocalDate.of(2026, 10, 15), NOW);

        assertThat(task.dueDate()).contains(LocalDate.of(2026, 10, 15));
        assertThat(task.plannedDate()).isEmpty();
    }

    @Test
    void completionRecordsWhoAndWhenAndCannotRepeat() {
        Task task = Task.create("Ablesen", "", anna, null, null, new TaskSource(DocumentId.random()), ben, NOW);

        task.complete(ben, NOW.plusSeconds(5));

        assertThat(task.status()).isEqualTo(TaskStatus.DONE);
        assertThat(task.completedBy()).contains(ben);
        assertThat(task.completedAt()).contains(NOW.plusSeconds(5));
        assertThat(task.owner()).contains(anna);
        assertThatThrownBy(() -> task.complete(anna, NOW)).isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "task.alreadyDone");

        task.reopen(NOW.plusSeconds(10));
        assertThat(task.isOpen()).isTrue();
        assertThat(task.completedAt()).isEmpty();
    }

    @Test
    void unassignedIsAnExplicitState() {
        Task task = Task.create("Wer kümmert sich?", null, null, null, null, null, anna, NOW);
        assertThat(task.isUnassigned()).isTrue();

        task.assign(ben, NOW);
        assertThat(task.owner()).contains(ben);

        task.assign(null, NOW);
        assertThat(task.isUnassigned()).isTrue();
    }

    @Test
    void validatesTitleAndNoteLength() {
        assertThatThrownBy(() -> Task.create(" ", null, null, null, null, null, anna, NOW))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> Task.create("x", "n".repeat(Task.MAX_NOTE_LENGTH + 1), null, null, null, null,
                anna, NOW)).isInstanceOf(InvalidInputException.class);
    }
}
