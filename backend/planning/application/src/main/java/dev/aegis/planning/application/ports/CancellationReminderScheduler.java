package dev.aegis.planning.application.ports;

import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.time.Instant;

/** Durable time trigger for one cancellation-review occurrence. */
public interface CancellationReminderScheduler {
    void schedule(TaskId taskId, MemberId requestedBy, Instant at);
}
