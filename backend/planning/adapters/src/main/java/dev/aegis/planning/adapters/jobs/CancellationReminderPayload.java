package dev.aegis.planning.adapters.jobs;

/** Stable job payload for one planning task occurrence. */
public record CancellationReminderPayload(int schemaVersion, String taskId, String requestedBy) {
    public static CancellationReminderPayload of(String taskId, String requestedBy) {
        return new CancellationReminderPayload(1, taskId, requestedBy);
    }
}
