package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.TaskId;
import java.time.LocalDate;
import java.util.List;

/** Shared task views. Both members see every task regardless of owner. */
public interface TaskQueries {

    /** Calendar integration state shown on Today. Nothing is simulated: connected is false until a provider exists. */
    record CalendarStatus(boolean connected, String provider, boolean readSupported, boolean writeSupported) {
    }

    record TodayView(LocalDate date, List<TaskSummary> overdue, List<TaskSummary> dueToday,
            List<TaskSummary> plannedToday, List<TaskSummary> upcoming, List<TaskSummary> unassigned,
            CalendarStatus calendar) {
    }

    TaskSummary get(Actor actor, TaskId id);

    /** {@code includeDone} false lists open tasks only. Tasks in the trash are never listed. */
    List<TaskSummary> list(Actor actor, boolean includeDone);

    /** Tasks linked to a document, except those in the trash. */
    List<TaskSummary> forDocument(Actor actor, DocumentId documentId);

    /** Tasks in the trash, most recently trashed first. */
    List<TaskSummary> trashed(Actor actor);

    TodayView today(Actor actor);
}
