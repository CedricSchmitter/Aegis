package dev.aegis.documents.adapters.jobs;

/** Persisted page editor job payload: the operation's stable id plus a schema version; never a serialized plan. */
public record PageEditPayload(int schemaVersion, String pageEditId) {
    public static final int CURRENT_SCHEMA = 1;

    public static PageEditPayload of(String pageEditId) {
        return new PageEditPayload(CURRENT_SCHEMA, pageEditId);
    }
}
