package dev.aegis.planning.application.ports;

import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;

/** Outbound planning events routed to the notice inbox by the composition root. */
public interface PlanningEvents {
    void taskAssigned(TaskId taskId, String title, MemberId assignee, MemberId assignedBy);

    void taskCompleted(TaskId taskId, String title, MemberId owner, MemberId completedBy);
}
