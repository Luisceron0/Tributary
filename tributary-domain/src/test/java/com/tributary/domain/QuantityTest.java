package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Example-based tests for {@link Quantity}.
 *
 * <p>Quantity is deliberately NOT scale 2. It is neither money nor tax, and the scale-2 rule
 * exists to make monetary values exact, not to truncate physical amounts. Forcing 0.125 hours
 * to 0.13 would corrupt {@code quantity x unit price} before any rounding decision is taken.
 */
class QuantityTest {

  @Test
  @DisplayName("keeps fractional precision that scale 2 would destroy")
  void keepsFractionalPrecision() {
    assertAll(
        () -> assertEquals(new BigDecimal("0.125000"), Quantity.of("0.125", "HUR").value()),
        () -> assertEquals(new BigDecimal("0.333333"), Quantity.of("0.3333334", "KGM").value()),
        () -> assertEquals(6, Quantity.of("1", "C62").value().scale()));
  }

  @Test
  @DisplayName("carries the EN 16931 unit of measure code (BT-130)")
  void carriesUnitCode() {
    assertEquals("C62", Quantity.of("3", "C62").unitCode());
  }

  @Test
  @DisplayName("rejects a negative quantity, and a missing or blank unit code")
  void rejectsInvalidInput() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> Quantity.of("-1", "C62")),
        () -> assertThrows(IllegalArgumentException.class, () -> Quantity.of("1", "")),
        () -> assertThrows(IllegalArgumentException.class, () -> Quantity.of("1", "   ")),
        () -> assertThrows(NullPointerException.class, () -> Quantity.of("1", null)));
  }

  @Test
  @DisplayName("allows zero — a zero-quantity line is a business rule concern, not a value concern")
  void allowsZero() {
    assertEquals(new BigDecimal("0.000000"), Quantity.of("0", "C62").value());
  }
}
