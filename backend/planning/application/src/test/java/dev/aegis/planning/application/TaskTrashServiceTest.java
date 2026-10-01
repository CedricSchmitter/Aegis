package dev.aegis.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.planning.application.api.TaskCommands.CreateTaskCommand;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.planning.domain.CancellationReview;
import dev.aegis.planning.domain.Task;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.PurgeConflictException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.TaskId;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Trash and restore of tasks (issue #48, ADR-0010) through the planning application services. */
class TaskTrashServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private final Actor asAnna = new Actor(anna);
    private PlanningTestSupport.InMemoryTaskRepository repository;
    private PlanningTestSupport.KnownDocuments documents;
    private PlanningTestSupport.Members access;
    private final List<ContractId> reconciled = new ArrayList<>();
    private final PlanningTestSupport.RecordingEvents events = new PlanningTestSupport.RecordingEvents();
    private TaskCommandService commands;
    private TaskQueryService queries;

    @BeforeEach
    void setUp() {
        repository = new PlanningTestSupport.InMemoryTaskRepository();
        documents = new PlanningTestSupport.KnownDocuments();
        access = new PlanningTestSupport.Members(anna, ben);
        rebuild();
    }

    private void rebuild() {
        var presenter = new TaskPresenter(documents);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        commands = new TaskCommandService(repository, documents, events,
                presenter, access, (actor, contract) -> reconciled.add(contract), Trash.RecoveryWindow.DEFAULT,
                clock);
        queries = new TaskQueryService(repository, new PlanningTestSupport.NoCalendar(), presenter, access, clock);
    }

    private TaskSummary create(String title, MemberId owner, DocumentId source) {
        return commands.create(asAnna, new CreateTaskCommand(title, "", owner, TODAY, null, source));
    }

    @Test
    void aTrashedTaskLeavesListsTodayAndTheDocumentPageButStaysReadable() {
        DocumentId invoice = DocumentId.random();
        documents.titles.put(invoice, "Stromrechnung");
        TaskSummary kept = create("Bezahlen", anna, invoice);
        TaskSummary trashed = create("Doppelt angelegt", null, invoice);

        TaskSummary result = commands.moveToTrash(asAnna, trashed.id(), trashed.version());

        assertThat(result.trash()).isEqualTo(new Trash(anna, NOW, NOW.plus(Duration.ofDays(30))));
        assertThat(queries.list(asAnna, true)).extracting(TaskSummary::id).containsExactly(kept.id());
        assertThat(queries.list(asAnna, false)).extracting(TaskSummary::id).containsExactly(kept.id());
        assertThat(queries.forDocument(asAnna, invoice)).extracting(TaskSummary::id).containsExactly(kept.id());
        assertThat(queries.today(asAnna).dueToday()).extracting(TaskSummary::id).containsExactly(kept.id());
        assertThat(queries.today(asAnna).unassigned()).isEmpty();
        assertThat(queries.trashed(asAnna)).extracting(TaskSummary::id).containsExactly(trashed.id());
        assertThat(queries.get(asAnna, trashed.id()).trash()).isNotNull();
        assertThat(queries.exportAll(asAnna)).hasSize(2);
    }

    @Test
    void restoringBringsTheTaskBackAndARepeatedRequestIsHarmless() {
        TaskSummary task = create("Zählerstand", ben, null);
        TaskSummary trashed = commands.moveToTrash(asAnna, task.id(), task.version());
        assertThat(commands.moveToTrash(new Actor(ben), task.id(), task.version()).trash())
                .isEqualTo(trashed.trash());
        assertThatThrownBy(() -> commands.restoreFromTrash(asAnna, task.id(), task.version()))
                .isInstanceOf(StaleVersionException.class);

        TaskSummary restored = commands.restoreFromTrash(asAnna, task.id(), trashed.version());

        assertThat(restored.trash()).isNull();
        assertThat(restored.ownerId()).isEqualTo(ben);
        assertThat(restored.status()).isEqualTo("OPEN");
        assertThat(commands.restoreFromTrash(asAnna, task.id(), trashed.version()).version())
                .isEqualTo(restored.version());
        assertThat(queries.today(asAnna).dueToday()).extracting(TaskSummary::id).containsExactly(task.id());
    }

    @Test
    void anOwnerWhoLeftTheHouseholdMeanwhileIsNotRestoredAsResponsible() {
        TaskSummary task = create("Versicherung kündigen", ben, null);
        TaskSummary trashed = commands.moveToTrash(asAnna, task.id(), task.version());
        access = new PlanningTestSupport.Members(anna);
        rebuild();

        TaskSummary restored = commands.restoreFromTrash(asAnna, task.id(), trashed.version());

        assertThat(restored.ownerId()).isNull();
        assertThat(queries.get(asAnna, task.id()).status()).isEqualTo("OPEN");
    }

    @Test
    void memberChangesWaitForTheRestore() {
        TaskSummary task = create("Rückruf", anna, null);
        TaskSummary trashed = commands.moveToTrash(asAnna, task.id(), task.version());

        assertThatThrownBy(() -> commands.complete(asAnna, task.id(), trashed.version()))
                .isInstanceOf(TrashedException.class).hasFieldOrPropertyWithValue("code", "task.trashed");
        assertThatThrownBy(() -> commands.assign(asAnna, task.id(), ben, trashed.version()))
                .isInstanceOf(TrashedException.class);
    }

    @Test
    void aTrashedSourceDocumentIsShownAsSuchAndCannotBeCitedByANewTask() {
        DocumentId scan = DocumentId.random();
        documents.titles.put(scan, "Scan 0815");
        TaskSummary task = create("Prüfen", anna, scan);
        documents.trashed.add(scan);

        assertThat(queries.get(asAnna, task.id()).source().documentTrashed()).isTrue();
        assertThat(queries.get(asAnna, task.id()).source().documentTitle()).isEqualTo("Scan 0815");
        assertThatThrownBy(() -> create("Neu", anna, scan)).isInstanceOf(TrashedException.class)
                .hasFieldOrPropertyWithValue("code", "document.trashed");
    }

    @Test
    void aRestoredCancellationReviewIsCheckedAgainstItsContractAtOnce() {
        ContractId contract = ContractId.random();
        Task review = Task.createCancellationReview(TaskId.random(), "Fitnessstudio", TODAY, null,
                new CancellationReview(contract, "Fitnessstudio", null, TODAY, TODAY.minusDays(7), 1), anna, NOW);
        repository.save(review);
        TaskSummary trashed = commands.moveToTrash(asAnna, review.id(), 0);
        TaskSummary manual = create("Manuell", anna, null);
        TaskSummary manualTrashed = commands.moveToTrash(asAnna, manual.id(), manual.version());

        commands.restoreFromTrash(asAnna, review.id(), trashed.version());
        commands.restoreFromTrash(asAnna, manual.id(), manualTrashed.version());

        assertThat(reconciled).containsExactly(contract);
    }

    @Test
    void onlyHouseholdMembersUseTheTrash() {
        TaskSummary task = create("Privat", anna, null);
        Actor stranger = new Actor(MemberId.random());

        assertThatThrownBy(() -> commands.moveToTrash(stranger, task.id(), task.version()))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> queries.trashed(stranger)).isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void onlyATrashedTaskIsDeletedPermanentlyAndARepeatedRequestIsHarmless() {
        TaskSummary task = create("Doppelt angelegt", anna, null);
        assertThatThrownBy(() -> commands.purge(asAnna, task.id(), task.version()))
                .isInstanceOf(PurgeConflictException.class).hasFieldOrPropertyWithValue("code", "task.purge.notTrashed");
        TaskSummary trashed = commands.moveToTrash(asAnna, task.id(), task.version());
        assertThatThrownBy(() -> commands.purge(asAnna, task.id(), trashed.version() + 1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> commands.purge(new Actor(MemberId.random()), task.id(), trashed.version()))
                .isInstanceOf(NotAuthorizedException.class);

        commands.purge(new Actor(ben), task.id(), trashed.version());

        assertThat(repository.findById(task.id())).isEmpty();
        assertThat(repository.wasPurged(task.id())).isTrue();
        assertThat(queries.trashed(asAnna)).isEmpty();
        assertThat(events.events).contains("purged:" + task.id());
        // After a lost response the same request succeeds without doing anything again.
        commands.purge(asAnna, task.id(), trashed.version());
        assertThat(events.events).filteredOn(event -> event.startsWith("purged:")).hasSize(1);
        assertThatThrownBy(() -> commands.purge(asAnna, TaskId.random(), 0)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTaskWhoseSourceWasDeletedPermanentlySaysSoInsteadOfLinkingToNothing() {
        DocumentId scan = DocumentId.random();
        documents.titles.put(scan, "Falscher Scan");
        TaskSummary task = create("Prüfen", anna, scan);
        assertThat(task.source().documentDeleted()).isFalse();

        documents.titles.remove(scan);

        TaskSummary.SourceView source = queries.get(asAnna, task.id()).source();
        assertThat(source.documentId()).isEqualTo(scan);
        assertThat(source.documentTitle()).isNull();
        assertThat(source.documentDeleted()).isTrue();
        assertThat(source.documentTrashed()).isFalse();
    }
}
