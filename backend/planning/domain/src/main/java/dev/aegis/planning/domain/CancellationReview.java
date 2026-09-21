package dev.aegis.planning.domain;

import dev.aegis.shared.ContractId;
import java.time.LocalDate;
import java.util.Objects;

/** Planning-owned snapshot used to present and reconcile one contractual cancellation-deadline occurrence. */
public record CancellationReview(ContractId contractId, String contractName, LocalDate contractEnd,
        LocalDate cancellationDeadline, LocalDate reminderDate, long contractVersion) {
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
}
