package dev.aegis.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.planning.application.api.TaskCommands.CreateTaskCommand;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private final Actor asAnna = new Actor(anna);
    private final Actor asBen = new Actor(ben);
    private PlanningTestSupport.InMemoryTaskRepository repository;
    private PlanningTestSupport.KnownDocuments documents;
    private PlanningTestSupport.RecordingEvents events;
    private TaskCommandService service;
    private TaskQueryService queries;

    @BeforeEach
    void setUp() {
        repository = new PlanningTestSupport.InMemoryTaskRepository();
        documents = new PlanningTestSupport.KnownDocuments();
        events = new PlanningTestSupport.RecordingEvents();
        var presenter = new TaskPresenter(documents);
        var access = new PlanningTestSupport.Members(anna, ben);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new TaskCommandService(repository, documents, events, presenter, access, clock);
        queries = new TaskQueryService(repository, new PlanningTestSupport.NoCalendar(), presenter, access, clock);
    }

    @Test
    void sourceLinkedTaskResolvesDocumentTitleAndRejectsUnknownDocuments() {
        DocumentId document = DocumentId.random();
        documents.titles.put(document, "Stromvertrag");

        TaskSummary task = service.create(asAnna, new CreateTaskCommand("Kündigen", "", null,
                LocalDate.of(2026, 12, 31), null, document));

        assertThat(task.source().documentTitle()).isEqualTo("Stromvertrag");
        assertThat(queries.forDocument(asBen, document)).extracting("id").containsExactly(task.id());
        assertThatThrownBy(() -> service.create(asAnna, new CreateTaskCommand("x", "", null, null, null,
                DocumentId.random()))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void assigningTheOtherMemberEmitsAnEventButSelfAssignmentDoesNot() {
        TaskSummary mine = service.create(asAnna, new CreateTaskCommand("Mine", "", anna, null, null, null));
        TaskSummary theirs = service.create(asAnna, new CreateTaskCommand("Theirs", "", ben, null, null, null));

        service.assign(asAnna, mine.id(), ben, mine.version());
        service.assign(asAnna, theirs.id(), ben, theirs.version());

        assertThat(events.events).containsExactly("assigned:" + theirs.id() + ":" + ben,
                "assigned:" + mine.id() + ":" + ben);
    }

    @Test
    void concurrentEditsOfBothMembersAreDetectedInsteadOfOverwritten() {
        TaskSummary created = service.create(asAnna, new CreateTaskCommand("Shared", "", null, null, null, null));

        TaskSummary afterAnna = service.edit(asAnna, created.id(), "Anna's title", "", created.version());

        assertThatThrownBy(() -> service.edit(asBen, created.id(), "Ben's title", "", created.version()))
                .isInstanceOf(StaleVersionException.class);
        assertThat(queries.get(asBen, created.id()).title()).isEqualTo("Anna's title");
        assertThat(service.edit(asBen, created.id(), "Ben's title", "", afterAnna.version()).title())
                .isEqualTo("Ben's title");
    }

    @Test
    void reschedulingWorkKeepsTheDeadlineAndCompletionIsVisibleToBoth() {
        LocalDate due = LocalDate.of(2026, 9, 30);
        TaskSummary task = service.create(asAnna, new CreateTaskCommand("Pay", "", ben, due, LocalDate.of(2026, 9,
                20), null));

        TaskSummary planned = service.planWork(asBen, task.id(), LocalDate.of(2026, 9, 28), task.version());
        assertThat(planned.plannedDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(planned.dueDate()).isEqualTo(due);

        TaskSummary done = service.complete(asAnna, task.id(), planned.version());
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.completedBy()).isEqualTo(anna);
        assertThat(queries.list(asBen, false)).isEmpty();
        assertThat(queries.list(asBen, true)).hasSize(1);
        assertThat(events.events).contains("completed:" + task.id() + ":" + ben);
    }
}
