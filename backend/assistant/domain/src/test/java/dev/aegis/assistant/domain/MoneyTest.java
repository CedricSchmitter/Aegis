package dev.aegis.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void keepsMicroUnitPrecisionAndComparesByValue() {
        Money a = Money.of("0.0000015", "USD");

        assertThat(a.amount().toPlainString()).isEqualTo("0.000002");
        assertThat(Money.of("1.5", "USD")).isEqualTo(Money.of("1.500000", "USD"));
        assertThat(Money.of("1.5", "USD").exceeds(Money.of("1.4", "USD"))).isTrue();
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of("1", "USD").plus(Money.of("1", "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
