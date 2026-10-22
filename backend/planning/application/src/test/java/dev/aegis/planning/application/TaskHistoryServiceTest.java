package dev.aegis.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.planning.application.api.TaskCommands.CreateTaskCommand;
import dev.aegis.planning.application.api.TaskHistoryQueries.TaskChangeView;
import dev.aegis.planning.application.api.TaskHistoryQueries.TaskHistoryPage;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.shared.Actor;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.TaskId;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import dev.aegis.shared.UndoConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskHistoryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final LocalDate DUE = LocalDate.of(2026, 11, 30);
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private final Actor asAnna = new Actor(anna);
    private final Actor asBen = new Actor(ben);
    private final PlanningTestSupport.InMemoryTaskRepository repository =
            new PlanningTestSupport.InMemoryTaskRepository();
    private final PlanningTestSupport.InMemoryTaskHistory history = new PlanningTestSupport.InMemoryTaskHistory();
    private final PlanningTestSupport.RecordingEvents events = new PlanningTestSupport.RecordingEvents();
    private PlanningTestSupport.Members access = new PlanningTestSupport.Members(anna, ben);
    private TaskCommandService commands;
    private TaskHistoryService queries;

    @BeforeEach
    void setUp() {
        rebuild();
    }

    private void rebuild() {
        var documents = new PlanningTestSupport.KnownDocuments();
        commands = new TaskCommandService(repository, history, documents, events, new TaskPresenter(documents),
                access, (actor, contract) -> { }, Trash.RecoveryWindow.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC));
        queries = new TaskHistoryService(repository, history, access);
    }

    private TaskSummary create(String title) {
        return commands.create(asAnna, new CreateTaskCommand(title, "", anna, DUE, null, null));
    }

    private TaskHistoryPage page(TaskId id) {
        return queries.history(asBen, id, null, 50);
    }

    private TaskChangeView latest(TaskId id) {
        return page(id).changes().getFirst();
    }

    @Test
    void eachMemberChangeIsRecordedWithItsAuthorAndChangedFieldsOnly() {
        TaskSummary task = create("Zählerstand");
        TaskSummary edited = commands.edit(asBen, task.id(), "Zählerstand melden", "", task.version());
        TaskSummary planned = commands.planWork(asAnna, task.id(), LocalDate.of(2026, 11, 2), edited.version());

        TaskHistoryPage page = page(task.id());

        assertThat(page.recordingSince()).isEqualTo(PlanningTestSupport.InMemoryTaskHistory.RECORDING_SINCE);
        assertThat(page.changes()).extracting(TaskChangeView::action).containsExactly("PLANNED", "EDITED", "CREATED");
        TaskChangeView plan = page.changes().get(0);
        assertThat(plan.actorKind()).isEqualTo("MEMBER");
        assertThat(plan.actorMember()).isEqualTo(anna);
        assertThat(plan.taskVersion()).isEqualTo(planned.version());
        assertThat(plan.changes()).containsExactly(new FieldChange("plannedDate", null, "2026-11-02"));
        assertThat(page.changes().get(1).actorMember()).isEqualTo(ben);
        assertThat(page.changes().get(1).changes())
                .containsExactly(new FieldChange("title", "Zählerstand", "Zählerstand melden"));
        assertThat(page.changes().get(2).undoable()).isFalse();
    }

    @Test
    void anEditThatChangesNothingLeavesNoEntry() {
        TaskSummary task = create("Rückruf");

        commands.edit(asAnna, task.id(), "Rückruf", "", task.version());

        assertThat(page(task.id()).changes()).extracting(TaskChangeView::action).containsExactly("CREATED");
    }

    @Test
    void undoRestoresTheEarlierValueAndIsRecordedAsANewChange() {
        TaskSummary task = create("Steuer");
        TaskSummary edited = commands.edit(asAnna, task.id(), "Steuererklärung", "Belege im Ordner", task.version());
        TaskChangeView edit = latest(task.id());
        assertThat(edit.undoable()).isTrue();

        TaskSummary undone = commands.undo(asBen, task.id(), edit.id(), edited.version());

        assertThat(undone.title()).isEqualTo("Steuer");
        assertThat(undone.note()).isEmpty();
        TaskHistoryPage page = page(task.id());
        assertThat(page.changes()).extracting(TaskChangeView::action).startsWith("UNDONE", "EDITED");
        assertThat(page.changes().get(0).undoOf()).isEqualTo(edit.id());
        assertThat(page.changes().get(0).actorMember()).isEqualTo(ben);
        assertThat(page.changes().get(0).undoable()).isFalse();
        assertThat(page.changes().get(1).undoneBy()).isEqualTo(page.changes().get(0).id());
        assertThat(page.changes().get(1).undoable()).isFalse();
        assertThatThrownBy(() -> commands.undo(asBen, task.id(), edit.id(), undone.version()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.alreadyUndone");
    }

    @Test
    void aLaterChangeOfTheSameFieldIsNeverOverwrittenButAnotherFieldDoesNotMatter() {
        TaskSummary task = create("Versicherung");
        TaskSummary first = commands.planWork(asAnna, task.id(), LocalDate.of(2026, 11, 1), task.version());
        TaskChangeView planning = latest(task.id());
        TaskSummary second = commands.planWork(asBen, task.id(), LocalDate.of(2026, 11, 8), first.version());

        assertThat(page(task.id()).changes().get(1).undoable()).isFalse();
        assertThatThrownBy(() -> commands.undo(asAnna, task.id(), planning.id(), second.version()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.changedSince")
                .satisfies(e -> assertThat(((UndoConflictException) e).details()).containsEntry("fields",
                        "plannedDate"));
        assertThat(repository.findById(task.id()).orElseThrow().plannedDate()).contains(LocalDate.of(2026, 11, 8));

        TaskChangeView latestPlan = latest(task.id());
        TaskSummary renamed = commands.edit(asAnna, task.id(), "Hausrat kündigen", "", second.version());
        TaskSummary undone = commands.undo(asAnna, task.id(), latestPlan.id(), renamed.version());

        assertThat(undone.plannedDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(undone.title()).isEqualTo("Hausrat kündigen");
    }

    @Test
    void undoingAPlannedDateNeverMovesTheDeadlineAndViceVersa() {
        TaskSummary task = create("Kündigen");
        TaskSummary planned = commands.planWork(asAnna, task.id(), LocalDate.of(2026, 11, 20), task.version());
        TaskChangeView plan = latest(task.id());
        TaskSummary moved = commands.changeDueDate(asAnna, task.id(), LocalDate.of(2026, 12, 15), planned.version());

        TaskSummary undone = commands.undo(asAnna, task.id(), plan.id(), moved.version());

        assertThat(undone.plannedDate()).isNull();
        assertThat(undone.dueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
    }

    @Test
    void undoingACompletionReopensAndUndoingAReopenCompletesAgain() {
        TaskSummary task = create("Müll");
        TaskSummary done = commands.complete(asBen, task.id(), task.version());
        TaskChangeView completion = latest(task.id());

        TaskSummary reopened = commands.undo(asAnna, task.id(), completion.id(), done.version());
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.completedBy()).isNull();

        TaskSummary doneAgain = commands.complete(asAnna, task.id(), reopened.version());
        TaskSummary reopenedByHand = commands.reopen(asAnna, task.id(), doneAgain.version());
        TaskSummary completedByUndo = commands.undo(asBen, task.id(), latest(task.id()).id(),
                reopenedByHand.version());
        assertThat(completedByUndo.status()).isEqualTo("DONE");
        assertThat(completedByUndo.completedBy()).isEqualTo(ben);
    }

    @Test
    void undoingAnAssignmentNotifiesTheEarlierOwnerAgainButRefusesAMemberWhoLeft() {
        TaskSummary task = create("Rasen");
        TaskSummary assigned = commands.assign(asAnna, task.id(), ben, task.version());
        TaskChangeView assignment = latest(task.id());
        events.events.clear();

        TaskSummary undone = commands.undo(asBen, task.id(), assignment.id(), assigned.version());
        assertThat(undone.ownerId()).isEqualTo(anna);
        assertThat(events.events).containsExactly("assigned:" + task.id() + ":" + anna);

        TaskSummary back = commands.assign(asAnna, task.id(), ben, undone.version());
        TaskSummary away = commands.assign(asAnna, task.id(), null, back.version());
        TaskChangeView unassignment = latest(task.id());
        access = new PlanningTestSupport.Members(anna);
        rebuild();
        assertThatThrownBy(() -> commands.undo(asAnna, task.id(), unassignment.id(), away.version()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.ownerInactive");
    }

    @Test
    void undoChecksTheVersionTheMemberSawAndWaitsForATrashedTaskToBeRestored() {
        TaskSummary task = create("Fenster");
        TaskSummary edited = commands.edit(asAnna, task.id(), "Fenster putzen", "", task.version());
        TaskChangeView edit = latest(task.id());

        assertThatThrownBy(() -> commands.undo(asAnna, task.id(), edit.id(), task.version()))
                .isInstanceOf(StaleVersionException.class);

        TaskSummary trashed = commands.moveToTrash(asAnna, task.id(), edited.version());
        assertThat(page(task.id()).changes().get(1).undoable()).isFalse();
        assertThatThrownBy(() -> commands.undo(asAnna, task.id(), edit.id(), trashed.version()))
                .isInstanceOf(TrashedException.class);
    }

    @Test
    void creationAndTheTrashAreRecordedButNotUndoneThroughHistory() {
        TaskSummary task = create("Doppelt");
        TaskSummary trashed = commands.moveToTrash(asAnna, task.id(), task.version());
        TaskSummary restored = commands.restoreFromTrash(asBen, task.id(), trashed.version());

        TaskHistoryPage page = page(task.id());
        assertThat(page.changes()).extracting(TaskChangeView::action).containsExactly("RESTORED", "TRASHED",
                "CREATED");
        assertThat(page.changes()).noneMatch(TaskChangeView::undoable);
        assertThatThrownBy(() -> commands.undo(asAnna, task.id(), page.changes().get(1).id(), restored.version()))
                .isInstanceOf(UndoConflictException.class)
                .hasFieldOrPropertyWithValue("code", "history.undo.notSupported");
    }

    @Test
    void aChangeOfAnotherTaskOrAnUnknownChangeIsNotFound() {
        TaskSummary one = create("Eins");
        TaskSummary two = create("Zwei");
        TaskSummary edited = commands.edit(asAnna, one.id(), "Eins!", "", one.version());
        UUID edit = latest(one.id()).id();

        assertThatThrownBy(() -> commands.undo(asAnna, two.id(), edit, two.version()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> commands.undo(asAnna, one.id(), UUID.randomUUID(), edited.version()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void historyIsPagedNewestFirstAndOnlyForMembers() {
        TaskSummary task = create("Seiten");
        long version = task.version();
        for (int i = 1; i <= 4; i++) {
            version = commands.edit(asAnna, task.id(), "Seiten " + i, "", version).version();
        }

        TaskHistoryPage first = queries.history(asAnna, task.id(), null, 3);
        TaskHistoryPage rest = queries.history(asAnna, task.id(), first.nextBeforeVersion(), 3);

        assertThat(first.changes()).hasSize(3);
        assertThat(rest.changes()).extracting(TaskChangeView::action).containsExactly("EDITED", "CREATED");
        assertThat(rest.nextBeforeVersion()).isNull();
        assertThatThrownBy(() -> queries.history(new Actor(MemberId.random()), task.id(), null, 3))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> queries.history(asAnna, task.id(), null, 0))
                .hasFieldOrPropertyWithValue("code", "validation.failed");
    }
}
