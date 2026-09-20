package dev.aegis.contracts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** Canonical value forms survive the round trip through persistence, API and export unchanged. */
class FactValueTest {

    @Test
    void everyKindRoundTripsThroughItsCanonicalText() {
        assertRoundTrip(new FactValue.TextValue("McFit GmbH"), "McFit GmbH");
        assertRoundTrip(new FactValue.DateValue(LocalDate.of(2028, 1, 1)), "2028-01-01");
        assertRoundTrip(new FactValue.PeriodValue(Period.ofMonths(3)), "P3M");
        assertRoundTrip(new FactValue.MoneyValue(Money.of("29.90", "EUR")), "29.90 EUR");
        assertRoundTrip(new FactValue.FlagValue(true), "true");
    }

    @Test
    void amountsKeepTheirCurrencyAndCompareByValue() {
        Money money = Money.of("29.90", "eur");
        assertThat(money.currency()).isEqualTo(Currency.getInstance("EUR"));
        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("29.9"));
        assertThat(money.sameAs(Money.of("29.9", "EUR"))).isTrue();
        // One canonical written form, whatever scale the database or an import hands back.
        assertThat(Money.of("29.9000", "EUR").canonicalText()).isEqualTo("29.90 EUR");
        assertThat(Money.of("1000", "EUR").canonicalText()).isEqualTo("1000.00 EUR");
        assertThat(money.sameAs(Money.of("29.90", "CHF"))).isFalse();
        assertThatThrownBy(() -> Money.of("29,90", "EUR")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aPeriodIsPositiveAndADateIsAValidCalendarDate() {
        assertThatThrownBy(() -> new FactValue.PeriodValue(Period.ofMonths(0)))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.fact.period.invalid");
        assertThatThrownBy(() -> FactValue.parse(ValueKind.DATE, "2028-02-31"))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.fact.value.invalid");
    }

    @Test
    void aValidityPeriodCoversTheDaysBetweenItsBounds() {
        ValidityPeriod year = new ValidityPeriod(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        assertThat(year.covers(LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(year.covers(LocalDate.of(2027, 12, 31))).isTrue();
        assertThat(year.covers(LocalDate.of(2026, 12, 31))).isFalse();
        assertThat(year.overlaps(ValidityPeriod.from(LocalDate.of(2027, 12, 31)))).isTrue();
        assertThat(year.overlaps(ValidityPeriod.from(LocalDate.of(2028, 1, 1)))).isFalse();
        assertThat(ValidityPeriod.always().covers(LocalDate.of(1999, 5, 5))).isTrue();
        assertThatThrownBy(() -> new ValidityPeriod(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 1, 1)))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.validity.invalid");
    }

    @Test
    void aSourceReferenceNeverInventsAPageNumber() {
        SourceReference withoutPage = SourceReference.document(DocumentId.random());
        assertThat(withoutPage.page()).isNull();
        assertThat(withoutPage.quote()).isNull();
        assertThat(new SourceReference(DocumentId.random(), 3, "  Kündigungsfrist 3 Monate  ").quote())
                .isEqualTo("Kündigungsfrist 3 Monate");
        assertThatThrownBy(() -> new SourceReference(DocumentId.random(), 0, null))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.source.page.invalid");
    }

    private static void assertRoundTrip(FactValue value, String canonicalText) {
        assertThat(value.canonicalText()).isEqualTo(canonicalText);
        FactValue parsed = FactValue.parse(value.kind(), canonicalText);
        assertThat(parsed.sameAs(value)).isTrue();
        assertThat(parsed.canonicalText()).isEqualTo(canonicalText);
    }
}
