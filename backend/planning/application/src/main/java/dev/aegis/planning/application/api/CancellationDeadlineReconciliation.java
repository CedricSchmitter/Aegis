package dev.aegis.planning.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;

/** Reconciles the latest confirmed contract deadline into planning-owned work. */
public interface CancellationDeadlineReconciliation {
    void reconcile(Actor actor, ContractId contractId);
}
