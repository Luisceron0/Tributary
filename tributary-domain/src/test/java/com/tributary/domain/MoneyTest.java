package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Example-based tests for {@link Money}. The rounding behaviour is pinned here with
 * values chosen to fail under any mode other than HALF_UP; the general arithmetic laws
 * live in {@code FiscalArithmeticPropertyTest}.
 */
class MoneyTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency COP = Currency.getInstance("COP");

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("normalises any input to scale 2")
    void normalisesToScaleTwo() {
      assertAll(
          () -> assertEquals(new BigDecimal("10.00"), Money.of("10", EUR).amount()),
          () -> assertEquals(new BigDecimal("10.00"), Money.of("10.0", EUR).amount()),
          () -> assertEquals(new BigDecimal("10.00"), Money.of("10.000", EUR).amount()),
          () -> assertEquals(2, Money.of("10", EUR).amount().scale()));
    }

    @Test
    @DisplayName("rounds HALF_UP, not HALF_EVEN — the tie goes away from zero")
    void roundsHalfUpAwayFromZero() {
      // HALF_EVEN would give 0.00 and -0.00 here. HALF_DOWN would give 0.00 and 0.00.
      // Only HALF_UP produces this pair, so this test fails if the mode ever drifts.
      assertAll(
          () -> assertEquals(new BigDecimal("0.01"), Money.of("0.005", EUR).amount()),
          () -> assertEquals(new BigDecimal("-0.01"), Money.of("-0.005", EUR).amount()),
          () -> assertEquals(new BigDecimal("2.35"), Money.of("2.345", EUR).amount()),
          () -> assertEquals(new BigDecimal("2.34"), Money.of("2.344", EUR).amount()));
    }

    @Test
    @DisplayName("rejects a null amount or currency")
    void rejectsNulls() {
      assertAll(
          () -> assertThrows(NullPointerException.class, () -> Money.of((BigDecimal) null, EUR)),
          () -> assertThrows(NullPointerException.class, () -> Money.of(BigDecimal.ONE, null)));
    }

    @Test
    @DisplayName("zero is scale 2 and carries its currency")
    void zeroIsScaleTwo() {
      assertAll(
          () -> assertEquals(new BigDecimal("0.00"), Money.zero(EUR).amount()),
          () -> assertEquals(EUR, Money.zero(EUR).currency()));
    }
  }

  @Nested
  @DisplayName("arithmetic")
  class Arithmetic {

    @Test
    @DisplayName("adds and subtracts within one currency")
    void addsAndSubtracts() {
      assertAll(
          () -> assertEquals(Money.of("30.00", EUR), Money.of("10.00", EUR).plus(Money.of("20.00", EUR))),
          () -> assertEquals(Money.of("-10.00", EUR), Money.of("10.00", EUR).minus(Money.of("20.00", EUR))));
    }

    @Test
    @DisplayName("multiplication rounds the result to scale 2 HALF_UP")
    void multiplicationRounds() {
      assertAll(
          // 10.00 x 3.3333 = 33.333000 -> 33.33
          () -> assertEquals(Money.of("33.33", EUR), Money.of("10.00", EUR).times(new BigDecimal("3.3333"))),
          // 0.10 x 0.05 = 0.0050, an exact tie. HALF_EVEN would give 0.00 (0 is even);
          // only HALF_UP gives 0.01.
          () -> assertEquals(Money.of("0.01", EUR), Money.of("0.10", EUR).times(new BigDecimal("0.05"))),
          // The operand is already normalised on construction: 3.335 became 3.34, so this is
          // 3.34 x 3, not 3.335 x 3. Rounding happens once, at the boundary, never twice.
          () -> assertEquals(Money.of("10.02", EUR), Money.of("3.335", EUR).times(new BigDecimal("3"))));
    }

    @Test
    @DisplayName("sums a collection, and an empty sum is zero in the stated currency")
    void sumsCollections() {
      assertAll(
          () -> assertEquals(
              Money.of("6.00", EUR),
              Money.sum(EUR, List.of(Money.of("1.00", EUR), Money.of("2.00", EUR), Money.of("3.00", EUR)))),
          () -> assertEquals(Money.zero(EUR), Money.sum(EUR, List.of())));
    }
  }

  @Nested
  @DisplayName("currency safety")
  class CurrencySafety {

    @Test
    @DisplayName("refuses to mix currencies instead of silently picking one")
    void refusesToMixCurrencies() {
      Money euros = Money.of("10.00", EUR);
      Money pesos = Money.of("10.00", COP);
      assertAll(
          () -> assertThrows(IllegalArgumentException.class, () -> euros.plus(pesos)),
          () -> assertThrows(IllegalArgumentException.class, () -> euros.minus(pesos)),
          () -> assertThrows(
              IllegalArgumentException.class, () -> Money.sum(EUR, List.of(euros, pesos))));
    }

    @Test
    @DisplayName("equal amounts in different currencies are not equal")
    void differentCurrenciesAreNotEqual() {
      org.junit.jupiter.api.Assertions.assertNotEquals(Money.of("10.00", EUR), Money.of("10.00", COP));
    }
  }
}
