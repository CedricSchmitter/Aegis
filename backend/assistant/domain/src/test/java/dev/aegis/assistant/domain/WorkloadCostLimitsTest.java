package dev.aegis.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkloadCostLimitsTest {
    private static final MemberId LISA = MemberId.random();
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private static Money usd(String amount) {
        return Money.of(amount, "USD");
    }

    @Test
    void untilSomebodySetsLimitsTheConfiguredDefaultsApply() {
        WorkloadCostLimits limits = WorkloadCostLimits.defaultOf(Workload.CHAT);

        assertThat(limits.own()).isFalse();
        assertThat(limits.dailyLimit()).isEmpty();
        assertThat(limits.runLimit()).isEmpty();
        assertThat(limits.version()).isZero();
    }

    @Test
    void aChangeNeedsTheCurrentVersionAndRecordsWhoChangedItWhen() {
        WorkloadCostLimits limits = WorkloadCostLimits.defaultOf(Workload.CHAT);

        assertThat(limits.change(usd("5.00"), usd("0.75"), 0, LISA, NOW)).isTrue();

        assertThat(limits.own()).isTrue();
        assertThat(limits.dailyLimit()).contains(usd("5"));
        assertThat(limits.runLimit()).contains(usd("0.75"));
        assertThat(limits.version()).isEqualTo(1);
        assertThat(limits.changedBy()).contains(LISA);
        assertThat(limits.changedAt()).contains(NOW);
        assertThatThrownBy(() -> limits.change(usd("1.00"), usd("0.10"), 0, LISA, NOW))
                .isInstanceOf(StaleVersionException.class);
        assertThat(limits.dailyLimit()).contains(usd("5"));
    }

    @Test
    void settingTheSameLimitsAgainChangesNothing() {
        WorkloadCostLimits limits = WorkloadCostLimits.restore(Workload.DOCUMENT_CLASSIFICATION, usd("1.00"),
                usd("0.05"), 3, LISA, NOW);

        assertThat(limits.change(usd("1"), usd("0.050"), 3, MemberId.random(), NOW.plusSeconds(60))).isFalse();

        assertThat(limits.version()).isEqualTo(3);
        assertThat(limits.changedBy()).contains(LISA);
    }

    @Test
    void limitsArePositiveAmountsInWholeCents() {
        WorkloadCostLimits limits = WorkloadCostLimits.defaultOf(Workload.DOCUMENT_REVIEW);

        assertThatThrownBy(() -> limits.change(usd("0"), usd("0.10"), 0, LISA, NOW))
                .isInstanceOfSatisfying(InvalidInputException.class, refused -> {
                    assertThat(refused.code()).isEqualTo("ai.limits.notPositive");
                    assertThat(refused.details()).containsEntry("field", "dailyCostLimit");
                });
        assertThatThrownBy(() -> limits.change(usd("1.00"), usd("-0.10"), 0, LISA, NOW))
                .isInstanceOfSatisfying(InvalidInputException.class, refused -> {
                    assertThat(refused.code()).isEqualTo("ai.limits.notPositive");
                    assertThat(refused.details()).containsEntry("field", "maxRunCost");
                });
        assertThatThrownBy(() -> limits.change(null, usd("0.10"), 0, LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "ai.limits.notPositive");
        assertThatThrownBy(() -> limits.change(usd("1.00"), usd("0.105"), 0, LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "ai.limits.tooPrecise");
        assertThatThrownBy(() -> limits.change(usd("1000.01"), usd("0.10"), 0, LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "ai.limits.tooHigh");
        assertThat(limits.version()).isZero();

        assertThat(limits.change(usd("1000"), usd("0.01"), 0, LISA, NOW)).isTrue();
    }

    @Test
    void aSessionMayNotCostMoreThanTheDayAllows() {
        WorkloadCostLimits limits = WorkloadCostLimits.defaultOf(Workload.CHAT);

        assertThatThrownBy(() -> limits.change(usd("0.50"), usd("0.51"), 0, LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "ai.limits.runAboveDaily");

        assertThat(limits.change(usd("0.50"), usd("0.50"), 0, LISA, NOW)).isTrue();
    }

    @Test
    void theConnectionCheckHasNoLimitsOfItsOwn() {
        assertThatThrownBy(() -> WorkloadCostLimits.defaultOf(Workload.CONNECTION_CHECK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedLimitsHaveBothAmounts() {
        assertThatThrownBy(() -> WorkloadCostLimits.restore(Workload.CHAT, usd("1"), null, 1, LISA, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkloadCostLimits.restore(Workload.CHAT, usd("1"), usd("0.1"), 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
