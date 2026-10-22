package dev.aegis.planning.domain.history;

import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.shared.FieldChange;
import dev.aegis.shared.MemberId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The recorded fields of a task at one moment, for computing a change and checking an undo against it. */
public record TaskSnapshot(String title, String note, MemberId owner, TaskStatus status, LocalDate dueDate,
        LocalDate plannedDate) {

    /** The state before a task existed: every field empty. */
    public static final TaskSnapshot NONE = new TaskSnapshot(null, null, null, null, null, null);

    public static TaskSnapshot of(Task task) {
        return new TaskSnapshot(task.title(), task.note(), task.owner().orElse(null), task.status(),
                task.dueDate().orElse(null), task.plannedDate().orElse(null));
    }

    /** The fields that differ from {@code before} to this snapshot, in field order. */
    public List<FieldChange> changesSince(TaskSnapshot before) {
        List<FieldChange> changes = new ArrayList<>();
        for (TaskField field : TaskField.values()) {
            String old = before.value(field);
            String now = value(field);
            if (!Objects.equals(old, now)) {
                changes.add(new FieldChange(field.code(), old, now));
            }
        }
        return changes;
    }

    /** The stored form of one field; an empty note counts as no note. */
    public String value(TaskField field) {
        return switch (field) {
            case TITLE -> title;
            case NOTE -> note == null || note.isEmpty() ? null : note;
            case OWNER -> owner == null ? null : owner.value().toString();
            case STATUS -> status == null ? null : status.name();
            case DUE_DATE -> dueDate == null ? null : dueDate.toString();
            case PLANNED_DATE -> plannedDate == null ? null : plannedDate.toString();
        };
    }
}
