package dev.aegis.documents.adapters.jobs;

/** Persisted purge job payload: stable id plus schema version; never a serialized aggregate. */
public record PurgeDocumentPayload(int schemaVersion, String documentId) {
    public static final int CURRENT_SCHEMA = 1;

    public static PurgeDocumentPayload of(String documentId) {
        return new PurgeDocumentPayload(CURRENT_SCHEMA, documentId);
    }
}
