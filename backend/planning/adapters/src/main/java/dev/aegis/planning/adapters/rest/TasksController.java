package dev.aegis.planning.adapters.rest;

import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.application.api.TaskCommands.CreateTaskCommand;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.api.TaskQueries.TodayView;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Shared tasks and the deterministic Today view. */
@RestController
@RequestMapping("/api")
@Tag(name = "tasks")
public class TasksController {
    private final TaskCommands commands;
    private final TaskQueries queries;

    public TasksController(TaskCommands commands, TaskQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    public record CreateTaskRequest(@NotBlank String title, @Schema(nullable = true) String note,
            @Schema(nullable = true) String ownerId, @Schema(nullable = true) LocalDate dueDate,
            @Schema(nullable = true) LocalDate plannedDate, @Schema(nullable = true) String sourceDocumentId) {
    }

    public record EditTaskRequest(@NotBlank String title, @Schema(nullable = true) String note, long expectedVersion) {
    }

    public record AssignRequest(@Schema(nullable = true) String ownerId, long expectedVersion) {
    }

    public record PlanWorkRequest(@Schema(nullable = true) LocalDate plannedDate, long expectedVersion) {
    }

    public record DueDateRequest(@Schema(nullable = true) LocalDate dueDate, long expectedVersion) {
    }

    /** Named explicitly because the label catalog and contracts publish their own {@code VersionRequest}. */
    @Schema(name = "TaskVersionRequest")
    public record VersionRequest(long expectedVersion) {
    }

    public record CalendarStatusDto(boolean connected, @Schema(nullable = true) String provider,
            boolean readSupported, boolean writeSupported) {
    }

    public record TodayResponse(LocalDate date, List<TaskDto> overdue, List<TaskDto> dueToday,
            List<TaskDto> plannedToday, List<TaskDto> upcoming, List<TaskDto> unassigned, CalendarStatusDto calendar) {
    }

    @Operation(operationId = "listTasks", summary = "List tasks; open only unless includeDone is set")
    @GetMapping("/tasks")
    public List<TaskDto> list(Actor actor, @RequestParam(name = "includeDone", defaultValue = "false") boolean includeDone,
            @RequestParam(name = "documentId", required = false) String documentId) {
        if (documentId != null && !documentId.isBlank()) {
            return queries.forDocument(actor, DocumentId.of(documentId)).stream().map(TaskDto::from).toList();
        }
        return queries.list(actor, includeDone).stream().map(TaskDto::from).toList();
    }

    @Operation(operationId = "createTask", summary = "Create a task, optionally linked to a document")
    @PostMapping("/tasks")
    public ResponseEntity<TaskDto> create(Actor actor, @Valid @RequestBody CreateTaskRequest body) {
        var created = commands.create(actor, new CreateTaskCommand(body.title(), body.note(),
                memberId(body.ownerId()), body.dueDate(), body.plannedDate(),
                body.sourceDocumentId() == null || body.sourceDocumentId().isBlank() ? null
                        : DocumentId.of(body.sourceDocumentId())));
        return ResponseEntity.status(HttpStatus.CREATED).body(TaskDto.from(created));
    }

    @Operation(operationId = "getTask", summary = "Task details")
    @GetMapping("/tasks/{id}")
    public TaskDto get(Actor actor, @PathVariable("id") String id) {
        return TaskDto.from(queries.get(actor, TaskId.of(id)));
    }

    @Operation(operationId = "editTask", summary = "Change title and note")
    @PutMapping("/tasks/{id}")
    public TaskDto edit(Actor actor, @PathVariable("id") String id, @Valid @RequestBody EditTaskRequest body) {
        return TaskDto.from(commands.edit(actor, TaskId.of(id), body.title(), body.note(), body.expectedVersion()));
    }

    @Operation(operationId = "assignTask", summary = "Assign one member or leave unassigned (ownerId null)")
    @PostMapping("/tasks/{id}/assign")
    public TaskDto assign(Actor actor, @PathVariable("id") String id, @RequestBody AssignRequest body) {
        return TaskDto.from(commands.assign(actor, TaskId.of(id), memberId(body.ownerId()), body.expectedVersion()));
    }

    @Operation(operationId = "planTaskWork", summary = "Move the planned work date; the due date stays")
    @PostMapping("/tasks/{id}/plan")
    public TaskDto plan(Actor actor, @PathVariable("id") String id, @RequestBody PlanWorkRequest body) {
        return TaskDto.from(commands.planWork(actor, TaskId.of(id), body.plannedDate(), body.expectedVersion()));
    }

    @Operation(operationId = "changeTaskDueDate", summary = "Explicitly change the actual deadline")
    @PostMapping("/tasks/{id}/due-date")
    public TaskDto dueDate(Actor actor, @PathVariable("id") String id, @RequestBody DueDateRequest body) {
        return TaskDto.from(commands.changeDueDate(actor, TaskId.of(id), body.dueDate(), body.expectedVersion()));
    }

    @Operation(operationId = "completeTask", summary = "Mark a task done")
    @PostMapping("/tasks/{id}/complete")
    public TaskDto complete(Actor actor, @PathVariable("id") String id, @RequestBody VersionRequest body) {
        return TaskDto.from(commands.complete(actor, TaskId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "listTrashedTasks", summary = "Tasks in the trash, most recently trashed first")
    @GetMapping("/tasks/trash")
    public List<TaskDto> trashed(Actor actor) {
        return map(queries.trashed(actor));
    }

    @Operation(operationId = "trashTask",
            summary = "Move a task to the trash; it leaves lists, Today and reminders and can be restored")
    @PostMapping("/tasks/{id}/trash")
    public TaskDto trash(Actor actor, @PathVariable("id") String id, @RequestBody VersionRequest body) {
        return TaskDto.from(commands.moveToTrash(actor, TaskId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "restoreTask", summary = "Bring a task back from the trash")
    @PostMapping("/tasks/{id}/restore")
    public TaskDto restore(Actor actor, @PathVariable("id") String id, @RequestBody VersionRequest body) {
        return TaskDto.from(commands.restoreFromTrash(actor, TaskId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "reopenTask", summary = "Reopen a completed task")
    @PostMapping("/tasks/{id}/reopen")
    public TaskDto reopen(Actor actor, @PathVariable("id") String id, @RequestBody VersionRequest body) {
        return TaskDto.from(commands.reopen(actor, TaskId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "today", summary = "Deterministic Today view computed from persisted tasks")
    @GetMapping("/today")
    public TodayResponse today(Actor actor) {
        TodayView view = queries.today(actor);
        return new TodayResponse(view.date(), map(view.overdue()), map(view.dueToday()), map(view.plannedToday()),
                map(view.upcoming()), map(view.unassigned()), new CalendarStatusDto(view.calendar().connected(),
                        view.calendar().provider(), view.calendar().readSupported(),
                        view.calendar().writeSupported()));
    }

    private static List<TaskDto> map(List<dev.aegis.planning.application.api.TaskSummary> tasks) {
        return tasks.stream().map(TaskDto::from).toList();
    }

    private static MemberId memberId(String raw) {
        return raw == null || raw.isBlank() ? null : MemberId.of(raw);
    }
}
