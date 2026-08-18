package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audit finding: nothing rejected a line carrying a non-zero {@code lineDiscount} (BT-136) on a
 * zero {@code quantity} (BT-129). A discount stated as a total is meaningless on zero units — there
 * is no per-unit price it could ever be "off" — and one regime adapter took that meaninglessness
 * literally: {@code CiiInvoiceMapper} converts the line-total discount into a per-unit figure for
 * CII's schema by dividing by quantity, so a zero-quantity discounted line reached {@code GET
 * /api/v1/invoices/{businessKey}/renderings/xrechnung} and threw an unhandled {@code
 * ArithmeticException} — a 500, not the endpoint's documented 422 "mapping constraint violated."
 *
 * <p>The fix sits in the domain rather than in the CII mapper alone: a discount on zero units is
 * not a CII-specific problem, it is not a real invoice line under any regime, and rejecting it at
 * construction protects every adapter, not just the one that happened to divide by it.
 */
class InvoiceLineZeroQuantityTest {

  private static final Currency EUR = Currency.getInstance("EUR");

  @Test
  @DisplayName("a non-zero discount on a zero-quantity line is rejected at construction")
  void discountOnZeroQuantityIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InvoiceLine.standardRate(
                "1", "Free sample", Quantity.of("0", "C62"), Money.of("0.00", EUR),
                Money.of("5.00", EUR), TaxRate.ofPercent("19")),
        "a discount cannot apply to zero units — this line asserts a total discount with nothing to discount from");
  }

  @Test
  @DisplayName("a zero-quantity line with no discount is still legitimate — e.g. a free sample")
  void zeroQuantityWithoutDiscountIsAllowed() {
    assertDoesNotThrow(
        () ->
            InvoiceLine.standardRate(
                "1", "Free sample", Quantity.of("0", "C62"), Money.of("0.00", EUR),
                Money.zero(EUR), TaxRate.ofPercent("19")));
  }

  @Test
  @DisplayName("a discounted line with a real quantity is unaffected")
  void discountOnPositiveQuantityIsAllowed() {
    assertDoesNotThrow(
        () ->
            InvoiceLine.standardRate(
                "1", "Widgets", Quantity.of("3", "C62"), Money.of("15.00", EUR),
                Money.of("5.00", EUR), TaxRate.ofPercent("19")));
  }
}
