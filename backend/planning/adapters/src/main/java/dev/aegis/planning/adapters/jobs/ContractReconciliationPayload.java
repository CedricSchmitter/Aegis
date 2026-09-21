package dev.aegis.planning.adapters.jobs;

/** Stable job payload; schema version permits compatible evolution without Java serialization. */
public record ContractReconciliationPayload(int schemaVersion, String contractId, String requestedBy,
        boolean recurring) {
    public static ContractReconciliationPayload oneShot(String contractId, String requestedBy) {
        return new ContractReconciliationPayload(1, contractId, requestedBy, false);
    }

    public static ContractReconciliationPayload recurring(String contractId, String requestedBy) {
        return new ContractReconciliationPayload(1, contractId, requestedBy, true);
    }
}
