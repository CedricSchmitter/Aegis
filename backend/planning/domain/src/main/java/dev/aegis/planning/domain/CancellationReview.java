package dev.aegis.planning.domain;

import dev.aegis.shared.ContractId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Planning-owned snapshot used to present and reconcile one contractual cancellation-deadline occurrence.
 * {@code contractDeleted} marks a completed occurrence kept as history after its contract was deleted permanently
 * (ADR-0021); its contract id then leads nowhere.
 */
public record CancellationReview(ContractId contractId, String contractName, LocalDate contractEnd,
        LocalDate cancellationDeadline, LocalDate reminderDate, long contractVersion, boolean contractDeleted) {
    public CancellationReview {
        Objects.requireNonNull(contractId, "contractId");
        if (contractName == null || contractName.isBlank()) {
            throw new IllegalArgumentException("contractName is required");
        }
        Objects.requireNonNull(cancellationDeadline, "cancellationDeadline");
        Objects.requireNonNull(reminderDate, "reminderDate");
        if (reminderDate.isAfter(cancellationDeadline)) {
            throw new IllegalArgumentException("reminderDate must not follow the deadline");
        }
    }

    /** An occurrence of a contract that exists. */
    public CancellationReview(ContractId contractId, String contractName, LocalDate contractEnd,
            LocalDate cancellationDeadline, LocalDate reminderDate, long contractVersion) {
        this(contractId, contractName, contractEnd, cancellationDeadline, reminderDate, contractVersion, false);
    }

    CancellationReview withContractDeleted() {
        return new CancellationReview(contractId, contractName, contractEnd, cancellationDeadline, reminderDate,
                contractVersion, true);
    }
}
