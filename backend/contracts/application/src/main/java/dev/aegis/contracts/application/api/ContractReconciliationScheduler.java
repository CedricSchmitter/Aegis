package dev.aegis.contracts.application.api;

import dev.aegis.shared.ContractId;
import dev.aegis.shared.MemberId;

/**
 * Public integration contract for durable work after a contract changes. Implementations store only stable
 * identities; planning reads the latest confirmed contract view when the job runs.
 */
public interface ContractReconciliationScheduler {
    void schedule(ContractId contractId, MemberId requestedBy);
}
