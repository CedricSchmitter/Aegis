package dev.aegis.contracts.domain.deadline;

import dev.aegis.contracts.domain.FactId;
import dev.aegis.contracts.domain.FactType;
import dev.aegis.shared.InvalidInputException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The outcome of calculating when a contract must be cancelled. Either the confirmed rules are sufficient and the
 * result names the contractual deadline and the facts it rests on, or they are not and the result says what is
 * missing or contradictory. There is no third case: Aegis never estimates a deadline.
 */
public sealed interface CancellationDeadline {

    /**
     * A contractual deadline: the last household day on which the cancellation has to be sent. It is neither a
     * planned work date nor a reminder date; planning owns those and may move them without touching this date.
     */
    record Determined(LocalDate deadline, LocalDate contractEnd, DeadlineBasis basis, List<FactId> basedOn)
            implements CancellationDeadline {

        public Determined {
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(basis, "basis");
            basedOn = List.copyOf(basedOn);
        }

        /**
         * The day a reminder about this deadline should first appear, {@code leadDays} before the deadline itself
         * and never before the contract end. The deadline stays where it is; only the reminder moves.
         */
        public LocalDate reminderDate(int leadDays) {
            if (leadDays < 0) {
                throw new InvalidInputException("contract.reminder.lead.invalid", "Lead days must not be negative");
            }
            return deadline.minusDays(leadDays);
        }

        public Optional<LocalDate> knownContractEnd() {
            return Optional.ofNullable(contractEnd);
        }
    }

    /**
     * The confirmed facts do not establish a deadline. {@code missing} names the facts that would be needed;
     * {@code contradicting} names types whose confirmed facts disagree and need a decision first.
     */
    record Insufficient(Set<FactType> missing, Set<FactType> contradicting) implements CancellationDeadline {

        public Insufficient {
            missing = Set.copyOf(missing);
            contradicting = Set.copyOf(contradicting);
        }
    }
}
