package dev.aegis.contracts.domain.deadline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.contracts.domain.Confidence;
import dev.aegis.contracts.domain.Contract;
import dev.aegis.contracts.domain.ContractFact;
import dev.aegis.contracts.domain.FactType;
import dev.aegis.contracts.domain.FactValue;
import dev.aegis.contracts.domain.SourceReference;
import dev.aegis.contracts.domain.ValidityPeriod;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Deterministic deadline calculation. Only confirmed facts count, the result says which rule it rests on, and
 * household local dates come from an injected clock in Europe/Berlin.
 */
class CancellationDeadlinePolicyTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final MemberId LISA = new MemberId(UUID.randomUUID());
    private static final DocumentId CONTRACT_PDF = DocumentId.random();
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    @Test
    void aStatedDeadlineIsUsedAsItIsAndTheReminderCountsBackFromIt() {
        Contract contract = contract();
        enter(contract, FactType.END_DATE, date(2028, 1, 1));
        enter(contract, FactType.CANCELLATION_DEADLINE, date(2027, 11, 30));

        CancellationDeadline result = CancellationDeadlinePolicy.calculate(contract, TODAY);

        assertThat(result).isInstanceOf(CancellationDeadline.Determined.class);
        CancellationDeadline.Determined determined = (CancellationDeadline.Determined) result;
        assertThat(determined.basis()).isEqualTo(DeadlineBasis.STATED_DEADLINE);
        assertThat(determined.deadline()).isEqualTo(LocalDate.of(2027, 11, 30));
        assertThat(determined.contractEnd()).isEqualTo(LocalDate.of(2028, 1, 1));
        assertThat(determined.basedOn()).hasSize(2);
        // Seven days before the cancellation deadline, not seven days before the contract end.
        assertThat(determined.reminderDate(7)).isEqualTo(LocalDate.of(2027, 11, 23));
    }

    @Test
    void withoutAStatedDeadlineTheNoticePeriodBeforeTheEndDecides() {
        Contract contract = contract();
        enter(contract, FactType.END_DATE, date(2028, 1, 1));
        enter(contract, FactType.NOTICE_PERIOD, months(3));

        CancellationDeadline.Determined determined = determined(contract, TODAY);

        assertThat(determined.basis()).isEqualTo(DeadlineBasis.NOTICE_PERIOD_BEFORE_END);
        assertThat(determined.deadline()).isEqualTo(LocalDate.of(2027, 10, 1));
        assertThat(determined.knownContractEnd()).contains(LocalDate.of(2028, 1, 1));
    }

    @Test
    void subtractingAPeriodFromAMonthEndStaysOnTheLastValidDay() {
        Contract leapYear = contract();
        enter(leapYear, FactType.END_DATE, date(2028, 3, 31));
        enter(leapYear, FactType.NOTICE_PERIOD, months(1));
        assertThat(determined(leapYear, TODAY).deadline()).isEqualTo(LocalDate.of(2028, 2, 29));

        Contract leapDay = contract();
        enter(leapDay, FactType.END_DATE, date(2028, 2, 29));
        enter(leapDay, FactType.NOTICE_PERIOD, new FactValue.PeriodValue(Period.ofYears(1)));
        assertThat(determined(leapDay, TODAY).deadline()).isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void anExtractedRuleAloneIsNotEnough() {
        Contract contract = contract();
        contract.observe(FactType.END_DATE, date(2028, 1, 1), ValidityPeriod.always(),
                SourceReference.document(CONTRACT_PDF), new Confidence(0.95), "run-1", NOW);
        contract.observe(FactType.NOTICE_PERIOD, months(3), ValidityPeriod.always(),
                SourceReference.document(CONTRACT_PDF), new Confidence(0.95), "run-1", NOW);

        CancellationDeadline result = CancellationDeadlinePolicy.calculate(contract, TODAY);

        assertThat(result).isInstanceOf(CancellationDeadline.Insufficient.class);
        assertThat(((CancellationDeadline.Insufficient) result).missing())
                .contains(FactType.END_DATE, FactType.NOTICE_PERIOD, FactType.CANCELLATION_DEADLINE);
    }

    @Test
    void aConfirmedFactBecomesEnoughOnlyAfterTheMissingRuleIsConfirmedToo() {
        Contract contract = contract();
        enter(contract, FactType.END_DATE, date(2028, 1, 1));
        ContractFact proposal = contract.observe(FactType.NOTICE_PERIOD, months(3), ValidityPeriod.always(),
                SourceReference.document(CONTRACT_PDF), new Confidence(0.95), "run-1", NOW).fact();

        assertThat(CancellationDeadlinePolicy.calculate(contract, TODAY))
                .isInstanceOf(CancellationDeadline.Insufficient.class);

        contract.confirm(proposal.id(), LISA, NOW);
        assertThat(determined(contract, TODAY).deadline()).isEqualTo(LocalDate.of(2027, 10, 1));
    }

    @Test
    void contradictingConfirmedRulesProduceNoDeadline() {
        Contract contract = contract();
        contract.enter(FactType.END_DATE, date(2028, 1, 1), new ValidityPeriod(LocalDate.of(2026, 1, 1), null),
                SourceReference.document(CONTRACT_PDF), null, LISA, NOW);
        contract.enter(FactType.END_DATE, date(2028, 6, 30),
                new ValidityPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1)),
                SourceReference.document(DocumentId.random()), null, LISA, NOW);
        enter(contract, FactType.NOTICE_PERIOD, months(3));

        CancellationDeadline result = CancellationDeadlinePolicy.calculate(contract, TODAY);

        assertThat(result).isInstanceOf(CancellationDeadline.Insufficient.class);
        assertThat(((CancellationDeadline.Insufficient) result).contradicting()).containsExactly(FactType.END_DATE);
    }

    @Test
    void anAmendedNoticePeriodAppliesFromItsOwnStartDate() {
        Contract contract = contract();
        enter(contract, FactType.END_DATE, date(2028, 1, 1));
        enter(contract, FactType.NOTICE_PERIOD, months(3));
        contract.enter(FactType.NOTICE_PERIOD, months(1), ValidityPeriod.from(LocalDate.of(2027, 1, 1)),
                SourceReference.document(DocumentId.random()), "Nachtrag", LISA, NOW);

        assertThat(determined(contract, LocalDate.of(2026, 12, 31)).deadline()).isEqualTo(LocalDate.of(2027, 10, 1));
        assertThat(determined(contract, LocalDate.of(2027, 1, 1)).deadline()).isEqualTo(LocalDate.of(2027, 12, 1));
    }

    @Test
    void theHouseholdDayComesFromTheInjectedClockInTheHouseholdZone() {
        Contract contract = contract();
        enter(contract, FactType.END_DATE, date(2028, 1, 1));
        enter(contract, FactType.CANCELLATION_DEADLINE, date(2027, 11, 30));

        // 22:30 UTC on 23 November is already 23:30 in Berlin: still the reminder day.
        Clock lateEvening = Clock.fixed(Instant.parse("2027-11-23T22:30:00Z"), BERLIN);
        assertThat(LocalDate.now(lateEvening)).isEqualTo(LocalDate.of(2027, 11, 23));
        // Half an hour later it is the next household day, although the UTC date has not changed yet.
        Clock afterMidnight = Clock.fixed(Instant.parse("2027-11-23T23:30:00Z"), BERLIN);
        assertThat(LocalDate.now(afterMidnight)).isEqualTo(LocalDate.of(2027, 11, 24));

        CancellationDeadline.Determined determined = determined(contract, LocalDate.now(lateEvening));
        assertThat(determined.deadline()).isEqualTo(LocalDate.of(2027, 11, 30));
        assertThat(determined.reminderDate(7)).isEqualTo(LocalDate.of(2027, 11, 23));
        // Summer time shifts the household day by two hours instead of one; the deadline itself never moves.
        Clock summerEvening = Clock.fixed(Instant.parse("2027-07-15T22:30:00Z"), BERLIN);
        assertThat(LocalDate.now(summerEvening)).isEqualTo(LocalDate.of(2027, 7, 16));
        assertThat(determined(contract, LocalDate.now(summerEvening)).deadline())
                .isEqualTo(LocalDate.of(2027, 11, 30));
    }

    private static Contract contract() {
        return Contract.create("Fitnessstudio", LISA, NOW);
    }

    private static CancellationDeadline.Determined determined(Contract contract, LocalDate on) {
        CancellationDeadline result = CancellationDeadlinePolicy.calculate(contract, on);
        assertThat(result).isInstanceOf(CancellationDeadline.Determined.class);
        return (CancellationDeadline.Determined) result;
    }

    private static void enter(Contract contract, FactType type, FactValue value) {
        contract.enter(type, value, ValidityPeriod.always(), SourceReference.document(CONTRACT_PDF), null, LISA,
                NOW);
    }

    private static FactValue date(int year, int month, int day) {
        return new FactValue.DateValue(LocalDate.of(year, month, day));
    }

    private static FactValue months(int months) {
        return new FactValue.PeriodValue(Period.ofMonths(months));
    }
}
