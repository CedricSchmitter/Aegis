package dev.aegis.contracts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.contracts.domain.Contract.ObservationOutcome;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Invariants of the contract aggregate: facts are appended with provenance and only a member decides. */
class ContractTest {
    private static final MemberId LISA = new MemberId(UUID.randomUUID());
    private static final MemberId CEDRIC = new MemberId(UUID.randomUUID());
    private static final DocumentId CONTRACT_PDF = DocumentId.random();
    private static final DocumentId AMENDMENT_PDF = DocumentId.random();
    private static final DocumentId INVOICE_PDF = DocumentId.random();
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    private Contract contract() {
        return Contract.create("Fitnessstudio", LISA, NOW);
    }

    @Test
    void aMemberEntersAFactAsConfirmedWithItsSource() {
        Contract contract = contract();
        ContractFact fact = contract.enter(FactType.END_DATE, new FactValue.DateValue(LocalDate.of(2028, 1, 1)),
                ValidityPeriod.always(), new SourceReference(CONTRACT_PDF, 2, "Vertragsende: 01.01.2028"), null,
                LISA, NOW);

        assertThat(fact.state()).isEqualTo(ReviewState.CONFIRMED);
        assertThat(fact.provenance().origin()).isEqualTo(FactOrigin.MANUAL);
        assertThat(fact.provenance().sourceReference()).get()
                .extracting(SourceReference::documentId, SourceReference::page)
                .containsExactly(CONTRACT_PDF, 2);
        assertThat(contract.effective(FactType.END_DATE, TODAY)).contains(fact);
        assertThat(contract.sources()).hasSize(1);
    }

    @Test
    void aFactValueMustMatchTheTypeOfTheFact() {
        Contract contract = contract();
        assertThatThrownBy(() -> contract.enter(FactType.END_DATE, new FactValue.TextValue("bald"),
                ValidityPeriod.always(), null, null, LISA, NOW))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.fact.value.kind");
    }

    @Test
    void anExtractionOnlyProposesAndNeverOverwritesAConfirmedFact() {
        Contract contract = contract();
        ContractFact confirmed = contract.enter(FactType.AMOUNT, amount("29.90"), ValidityPeriod.always(), null,
                null, LISA, NOW);

        Contract.Observation observation = contract.observe(FactType.AMOUNT, amount("39.90"),
                ValidityPeriod.always(), SourceReference.document(INVOICE_PDF), new Confidence(0.9), "run-1", NOW);

        assertThat(observation.outcome()).isEqualTo(ObservationOutcome.CONTRADICTS_CONFIRMED);
        assertThat(observation.fact().state()).isEqualTo(ReviewState.EXTRACTED);
        assertThat(contract.effective(FactType.AMOUNT, TODAY)).contains(confirmed);
        assertThat(contract.conflicts(TODAY)).singleElement()
                .extracting(FactConflict::kind)
                .isEqualTo(FactConflict.ConflictKind.EXTRACTED_AGAINST_CONFIRMED);
    }

    @Test
    void repeatingAKnownProposalStoresNothingTwice() {
        Contract contract = contract();
        contract.enter(FactType.AMOUNT, amount("29.90"), ValidityPeriod.always(), null, null, LISA, NOW);

        Contract.Observation again = contract.observe(FactType.AMOUNT, amount("29.9"), ValidityPeriod.always(),
                SourceReference.document(CONTRACT_PDF), new Confidence(0.8), "run-2", NOW);
        assertThat(again.outcome()).isEqualTo(ObservationOutcome.ALREADY_CONFIRMED);

        Contract.Observation proposal = contract.observe(FactType.CONTRACT_NUMBER, new FactValue.TextValue("A-17"),
                ValidityPeriod.always(), SourceReference.document(CONTRACT_PDF), null, "run-2", NOW);
        Contract.Observation repeat = contract.observe(FactType.CONTRACT_NUMBER, new FactValue.TextValue("A-17"),
                ValidityPeriod.always(), SourceReference.document(CONTRACT_PDF), null, "run-3", NOW);
        assertThat(proposal.outcome()).isEqualTo(ObservationOutcome.RECORDED);
        assertThat(repeat.outcome()).isEqualTo(ObservationOutcome.ALREADY_PROPOSED);
        assertThat(contract.history(FactType.CONTRACT_NUMBER)).hasSize(1);
        assertThat(contract.history(FactType.AMOUNT)).hasSize(1);
    }

    @Test
    void confirmingAProposalSupersedesTheConfirmedFactItReplaces() {
        Contract contract = contract();
        ContractFact original = contract.enter(FactType.AMOUNT, amount("29.90"), ValidityPeriod.always(), null,
                null, LISA, NOW);
        ContractFact proposed = contract.observe(FactType.AMOUNT, amount("39.90"), ValidityPeriod.always(),
                SourceReference.document(INVOICE_PDF), new Confidence(0.9), "run-1", NOW).fact();

        contract.confirm(proposed.id(), CEDRIC, NOW);

        assertThat(original.state()).isEqualTo(ReviewState.SUPERSEDED);
        assertThat(original.supersededBy()).contains(proposed.id());
        assertThat(proposed.decidedBy()).contains(CEDRIC);
        assertThat(contract.effective(FactType.AMOUNT, TODAY)).contains(proposed);
        assertThat(contract.conflicts(TODAY)).isEmpty();
    }

    @Test
    void aRejectedProposalIsNeitherEffectiveNorAConflict() {
        Contract contract = contract();
        contract.enter(FactType.AMOUNT, amount("29.90"), ValidityPeriod.always(), null, null, LISA, NOW);
        ContractFact proposed = contract.observe(FactType.AMOUNT, amount("39.90"), ValidityPeriod.always(),
                SourceReference.document(INVOICE_PDF), new Confidence(0.4), "run-1", NOW).fact();

        contract.reject(proposed.id(), LISA, NOW);

        assertThat(proposed.state()).isEqualTo(ReviewState.REJECTED);
        assertThat(contract.conflicts(TODAY)).isEmpty();
        assertThat(contract.effective(FactType.AMOUNT, TODAY)).get()
                .extracting(fact -> fact.value().canonicalText()).isEqualTo("29.90 EUR");
        assertThat(contract.history(FactType.AMOUNT)).hasSize(2);
    }

    @Test
    void aCorrectionKeepsTheSourceAndTheCorrectedObservation() {
        Contract contract = contract();
        ContractFact wrong = contract.observe(FactType.END_DATE, new FactValue.DateValue(LocalDate.of(2028, 1, 10)),
                ValidityPeriod.always(), new SourceReference(CONTRACT_PDF, 2, "Vertragsende"), new Confidence(0.6),
                "run-1", NOW).fact();

        ContractFact corrected = contract.correct(wrong.id(), new FactValue.DateValue(LocalDate.of(2028, 1, 1)),
                ValidityPeriod.always(), "Zahlendreher", CEDRIC, NOW);

        assertThat(wrong.state()).isEqualTo(ReviewState.SUPERSEDED);
        assertThat(wrong.supersededBy()).contains(corrected.id());
        assertThat(corrected.corrects()).contains(wrong.id());
        assertThat(corrected.state()).isEqualTo(ReviewState.CONFIRMED);
        assertThat(corrected.provenance().origin()).isEqualTo(FactOrigin.MANUAL);
        assertThat(corrected.provenance().sourceReference()).get()
                .extracting(SourceReference::documentId).isEqualTo(CONTRACT_PDF);
        assertThat(contract.effective(FactType.END_DATE, TODAY)).contains(corrected);
    }

    @Test
    void anAmendmentTakesOverFromItsOwnStartDateAndLeavesTheOriginalReadable() {
        Contract contract = contract();
        ContractFact original = contract.enter(FactType.AMOUNT, amount("29.90"), ValidityPeriod.always(),
                SourceReference.document(CONTRACT_PDF), null, LISA, NOW);
        ContractFact amendment = contract.enter(FactType.AMOUNT, amount("34.90"),
                ValidityPeriod.from(LocalDate.of(2027, 1, 1)), SourceReference.document(AMENDMENT_PDF), null,
                LISA, NOW);

        assertThat(original.state()).isEqualTo(ReviewState.CONFIRMED);
        assertThat(contract.effective(FactType.AMOUNT, LocalDate.of(2026, 12, 31))).contains(original);
        assertThat(contract.effective(FactType.AMOUNT, LocalDate.of(2027, 1, 1))).contains(amendment);
        assertThat(contract.conflicts(LocalDate.of(2027, 1, 1))).isEmpty();
        assertThat(contract.sources()).hasSize(2);
    }

    @Test
    void twoConfirmedFactsForTheSameDayAreReportedInsteadOfPicked() {
        Contract contract = contract();
        contract.enter(FactType.END_DATE, new FactValue.DateValue(LocalDate.of(2028, 1, 1)),
                new ValidityPeriod(LocalDate.of(2026, 1, 1), null), SourceReference.document(CONTRACT_PDF), null,
                LISA, NOW);
        contract.enter(FactType.END_DATE, new FactValue.DateValue(LocalDate.of(2028, 6, 30)),
                new ValidityPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1)),
                SourceReference.document(INVOICE_PDF), null, CEDRIC, NOW);

        assertThat(contract.ambiguous(FactType.END_DATE, TODAY)).isTrue();
        assertThat(contract.effective(FactType.END_DATE, TODAY)).isEmpty();
        assertThat(contract.conflicts(TODAY)).singleElement().extracting(FactConflict::kind)
                .isEqualTo(FactConflict.ConflictKind.CONFIRMED_AGAINST_CONFIRMED);
    }

    @Test
    void anAlreadyConfirmedFactIsNotConfirmedTwiceAndASupersededOneNotAtAll() {
        Contract contract = contract();
        ContractFact fact = contract.enter(FactType.NOTICE_PERIOD, new FactValue.PeriodValue(Period.ofMonths(3)),
                ValidityPeriod.always(), null, null, LISA, NOW);

        assertThatThrownBy(() -> contract.confirm(fact.id(), LISA, NOW))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.fact.alreadyConfirmed");

        ContractFact replacement = contract.enter(FactType.NOTICE_PERIOD,
                new FactValue.PeriodValue(Period.ofMonths(1)), ValidityPeriod.always(), null, null, CEDRIC, NOW);
        assertThat(fact.state()).isEqualTo(ReviewState.SUPERSEDED);
        assertThatThrownBy(() -> contract.correct(fact.id(), new FactValue.PeriodValue(Period.ofMonths(2)),
                ValidityPeriod.always(), null, LISA, NOW))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.fact.superseded");
        assertThat(contract.effective(FactType.NOTICE_PERIOD, TODAY)).contains(replacement);
    }

    @Test
    void anExtractionMustNameTheDocumentItReadAndAMemberStatementCarriesNoConfidence() {
        Contract contract = contract();
        assertThatThrownBy(() -> contract.observe(FactType.COUNTERPARTY, new FactValue.TextValue("McFit"),
                ValidityPeriod.always(), null, new Confidence(0.9), "run-1", NOW))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.source.missing");

        assertThatThrownBy(() -> new FactProvenance(FactOrigin.MANUAL, null, new Confidence(0.9), null, LISA, NOW))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.provenance.invalid");
    }

    @Test
    void historyKeepsEveryObservationNewestFirst() {
        Contract contract = contract();
        ContractFact first = contract.enter(FactType.AMOUNT, amount("29.90"), ValidityPeriod.always(), null, null,
                LISA, NOW);
        ContractFact second = contract.enter(FactType.AMOUNT, amount("31.90"), ValidityPeriod.always(), null, null,
                LISA, NOW.plusSeconds(60));

        assertThat(contract.history(FactType.AMOUNT)).containsExactly(second, first);
        assertThat(contract.updatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    private static FactValue amount(String value) {
        return new FactValue.MoneyValue(Money.of(value, "EUR"));
    }
}
