package dev.aegis.contracts.domain.deadline;

import dev.aegis.contracts.domain.Contract;
import dev.aegis.contracts.domain.ContractFact;
import dev.aegis.contracts.domain.FactId;
import dev.aegis.contracts.domain.FactType;
import dev.aegis.contracts.domain.FactValue;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic cancellation deadlines from confirmed contract facts.
 *
 * <p>Only confirmed facts count. An extracted proposal, a rejected value or a superseded observation never
 * produces a deadline, and contradicting confirmed facts produce none either. All dates are household local
 * dates (the contract states calendar days, not instants); the caller decides which day "today" is by passing a
 * date derived from an injected clock in the household time zone.
 *
 * <p>Two rules are supported, in this order:
 * <ol>
 *   <li>a stated cancellation deadline is used as it is;</li>
 *   <li>otherwise the contract end minus the notice period, so a notice period of three months before an end on
 *       2028-01-01 has to be sent by 2027-10-01. Subtracting a period from a month end keeps the last valid day
 *       of the shorter month, which is what {@link LocalDate#minus(java.time.temporal.TemporalAmount)} does.</li>
 * </ol>
 */
public final class CancellationDeadlinePolicy {

    private CancellationDeadlinePolicy() {
    }

    /**
     * Calculates the deadline from the facts that are confirmed and valid on {@code on}, usually today.
     *
     * @param contract the contract whose facts are read; nothing is mutated
     * @param on the household local date the rules are read for
     */
    public static CancellationDeadline calculate(Contract contract, LocalDate on) {
        Set<FactType> contradicting = EnumSet.noneOf(FactType.class);
        for (FactType type : List.of(FactType.CANCELLATION_DEADLINE, FactType.END_DATE, FactType.NOTICE_PERIOD)) {
            if (contract.ambiguous(type, on)) {
                contradicting.add(type);
            }
        }
        Optional<ContractFact> stated = contract.effective(FactType.CANCELLATION_DEADLINE, on);
        Optional<ContractFact> end = contract.effective(FactType.END_DATE, on);
        Optional<ContractFact> notice = contract.effective(FactType.NOTICE_PERIOD, on);

        if (stated.isPresent()) {
            List<FactId> basedOn = new ArrayList<>();
            basedOn.add(stated.get().id());
            end.ifPresent(fact -> basedOn.add(fact.id()));
            return new CancellationDeadline.Determined(date(stated.get()), end.map(
                    CancellationDeadlinePolicy::date).orElse(null), DeadlineBasis.STATED_DEADLINE, basedOn);
        }
        if (end.isPresent() && notice.isPresent()) {
            LocalDate endDate = date(end.get());
            Period noticePeriod = period(notice.get());
            return new CancellationDeadline.Determined(endDate.minus(noticePeriod), endDate,
                    DeadlineBasis.NOTICE_PERIOD_BEFORE_END, List.of(end.get().id(), notice.get().id()));
        }
        Set<FactType> missing = EnumSet.noneOf(FactType.class);
        if (end.isEmpty()) {
            missing.add(FactType.END_DATE);
        }
        if (notice.isEmpty()) {
            missing.add(FactType.NOTICE_PERIOD);
        }
        missing.add(FactType.CANCELLATION_DEADLINE);
        return new CancellationDeadline.Insufficient(missing, contradicting);
    }

    private static LocalDate date(ContractFact fact) {
        return ((FactValue.DateValue) fact.value()).date();
    }

    private static Period period(ContractFact fact) {
        return ((FactValue.PeriodValue) fact.value()).period();
    }
}
