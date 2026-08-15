package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Example-based tests for {@link TaxRate}. Rates cover RC-1 (19%), RC-2 (19% + 7%) and RC-3 (0%). */
class TaxRateTest {

  private static final Currency EUR = Currency.getInstance("EUR");

  @Test
  @DisplayName("normalises the percentage to scale 2")
  void normalisesToScaleTwo() {
    assertAll(
        () -> assertEquals(2, TaxRate.ofPercent("19").percentage().scale()),
        () -> assertEquals(TaxRate.ofPercent("19.00"), TaxRate.ofPercent("19")),
        () -> assertEquals(TaxRate.ofPercent("7.00"), TaxRate.ofPercent("7.0")));
  }

  @Test
  @DisplayName("computes tax on a base, rounded to scale 2 HALF_UP")
  void computesTaxOnBase() {
    assertAll(
        // RC-1: 19% of 100.00 = 19.00
        () -> assertEquals(Money.of("19.00", EUR), TaxRate.ofPercent("19").taxOn(Money.of("100.00", EUR))),
        // RC-2: 7% of 10.05 = 0.7035 -> 0.70
        () -> assertEquals(Money.of("0.70", EUR), TaxRate.ofPercent("7").taxOn(Money.of("10.05", EUR))),
        // An exact tie chosen to discriminate the rounding mode: 50% of 0.17 = 0.085.
        // HALF_EVEN would give 0.08 (8 is even); only HALF_UP gives 0.09.
        () -> assertEquals(Money.of("0.09", EUR), TaxRate.ofPercent("50").taxOn(Money.of("0.17", EUR))));
  }

  @Test
  @DisplayName("a zero rate yields zero tax in the base currency — RC-3 reverse charge")
  void zeroRateYieldsZeroTax() {
    Money base = Money.of("1234.56", EUR);
    assertAll(
        () -> assertEquals(Money.zero(EUR), TaxRate.zero().taxOn(base)),
        () -> assertTrue(TaxRate.zero().isZero()),
        () -> assertEquals(EUR, TaxRate.zero().taxOn(base).currency()));
  }

  @Test
  @DisplayName("rejects a negative rate and a rate above 100")
  void rejectsOutOfRangeRates() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> TaxRate.ofPercent("-0.01")),
        () -> assertThrows(IllegalArgumentException.class, () -> TaxRate.ofPercent("100.01")),
        () -> assertThrows(NullPointerException.class, () -> TaxRate.ofPercent(null)));
  }
}
