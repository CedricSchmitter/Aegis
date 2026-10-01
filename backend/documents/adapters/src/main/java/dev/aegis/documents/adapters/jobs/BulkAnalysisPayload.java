package dev.aegis.documents.adapters.jobs;

/** Persisted bulk analysis job payload: the run's stable id plus a schema version; never a serialized aggregate. */
public record BulkAnalysisPayload(int schemaVersion, String bulkId) {
    public static final int CURRENT_SCHEMA = 1;

    public static BulkAnalysisPayload of(String bulkId) {
        return new BulkAnalysisPayload(CURRENT_SCHEMA, bulkId);
    }
}
