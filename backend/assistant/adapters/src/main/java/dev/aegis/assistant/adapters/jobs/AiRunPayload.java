package dev.aegis.assistant.adapters.jobs;

/** Persisted job payload: the run id plus a schema version; the run itself lives in {@code ai_runs}. */
public record AiRunPayload(int schemaVersion, String runId) {
    public static final int CURRENT_SCHEMA = 1;

    public static AiRunPayload of(String runId) {
        return new AiRunPayload(CURRENT_SCHEMA, runId);
    }
}
