package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.TaskId;

/** Publishes a due cancellation reminder after rechecking the planning state. */
public interface CancellationReminderPublication {
    void publish(Actor actor, TaskId taskId);
}
