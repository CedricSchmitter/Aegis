package dev.aegis.documents.adapters.jobs;

/** Persisted job payload: stable id plus schema version; never a serialized aggregate. */
public record ArchiveDocumentPayload(int schemaVersion, String documentId) {
    public static final int CURRENT_SCHEMA = 1;

    public static ArchiveDocumentPayload of(String documentId) {
        return new ArchiveDocumentPayload(CURRENT_SCHEMA, documentId);
    }
}
