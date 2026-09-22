package dev.aegis.contracts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** A trashed contract keeps every fact; members restore it before they change anything. */
class ContractTrashTest {
    private static final MemberId LISA = MemberId.random();
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final DocumentId CONTRACT_PDF = DocumentId.random();

    @Test
    void trashingKeepsTheFactsAndRefusesMemberDecisionsUntilRestored() {
        Contract contract = Contract.create("Fitnessstudio", LISA, NOW);
        ContractFact end = contract.enter(FactType.END_DATE, new FactValue.DateValue(LocalDate.of(2028, 1, 1)),
                ValidityPeriod.always(), new SourceReference(CONTRACT_PDF, 1, "Vertragsende"), null, LISA, NOW);

        assertThat(contract.moveToTrash(LISA, NOW, Trash.RecoveryWindow.DEFAULT)).isTrue();
        assertThat(contract.moveToTrash(LISA, NOW, Trash.RecoveryWindow.DEFAULT)).isFalse();

        assertThat(contract.facts()).containsExactly(end);
        assertThatThrownBy(() -> contract.rename("Neu", NOW)).isInstanceOf(TrashedException.class)
                .hasFieldOrPropertyWithValue("code", "contract.trashed");
        assertThatThrownBy(() -> contract.changeStatus(ContractStatus.CANCELLED, NOW))
                .isInstanceOf(TrashedException.class);
        assertThatThrownBy(() -> contract.enter(FactType.NOTICE_PERIOD, new FactValue.PeriodValue(
                java.time.Period.ofMonths(3)), ValidityPeriod.always(), null, null, LISA, NOW))
                .isInstanceOf(TrashedException.class);
        assertThatThrownBy(() -> contract.reject(end.id(), LISA, NOW)).isInstanceOf(TrashedException.class);

        assertThat(contract.restoreFromTrash(NOW.plusSeconds(1))).isTrue();
        assertThat(contract.restoreFromTrash(NOW.plusSeconds(2))).isFalse();
        contract.rename("Fitnessstudio Nordpark", NOW.plusSeconds(3));
        assertThat(contract.name()).isEqualTo("Fitnessstudio Nordpark");
    }

    @Test
    void anExtractionRunThatWasAlreadyReadingMayStillRecordItsProposal() {
        Contract contract = Contract.create("Strom", LISA, NOW);
        contract.moveToTrash(LISA, NOW, Trash.RecoveryWindow.DEFAULT);

        Contract.Observation observation = contract.observe(FactType.END_DATE,
                new FactValue.DateValue(LocalDate.of(2027, 6, 30)), ValidityPeriod.always(),
                new SourceReference(CONTRACT_PDF, null, null), new Confidence(0.9), "run:1", NOW);

        assertThat(observation.outcome()).isEqualTo(Contract.ObservationOutcome.RECORDED);
        assertThat(contract.pendingFacts()).hasSize(1);
    }
}
