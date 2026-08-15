package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link InvoiceLine} models BG-25. */
class InvoiceLineTest {

  private static final Currency EUR = Currency.getInstance("EUR");

  @Test
  @DisplayName("BT-131 net amount = quantity x unit price, no discount")
  void netAmountWithoutDiscount() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Consulting hours", Quantity.of("2", "C62"), Money.of("10.00", EUR),
            Money.zero(EUR), TaxRate.ofPercent("19"));
    assertEquals(Money.of("20.00", EUR), line.netAmount());
  }

  @Test
  @DisplayName("BT-131 net amount subtracts the line discount (BT-136)")
  void netAmountWithDiscount() {
    // 3 x 15.00 - 5.00 = 40.00
    InvoiceLine line =
        InvoiceLine.standardRate(
            "2", "Widgets", Quantity.of("3", "C62"), Money.of("15.00", EUR),
            Money.of("5.00", EUR), TaxRate.ofPercent("19"));
    assertEquals(Money.of("40.00", EUR), line.netAmount());
  }

  @Test
  @DisplayName("a reverse-charge line carries a zero rate and an exemption reason")
  void reverseChargeLine() {
    InvoiceLine line =
        InvoiceLine.reverseCharge(
            "1", "Consulting hours", Quantity.of("1", "C62"), Money.of("200.00", EUR),
            Money.zero(EUR), "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");
    assertAll(
        () -> assertEquals(TaxCategory.REVERSE_CHARGE, line.taxCategory()),
        () -> assertTrue(line.taxRate().isZero()),
        () -> assertTrue(line.vatExemptionReason().isPresent()));
  }

  @Test
  @DisplayName("a standard-rate line has no exemption reason")
  void standardRateLineHasNoExemptionReason() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("10.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    assertFalse(line.vatExemptionReason().isPresent());
  }

  @Test
  void rejectsANegativeLineDiscount() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InvoiceLine.standardRate(
                "1", "Widgets", Quantity.of("1", "C62"), Money.of("10.00", EUR),
                Money.of("-0.01", EUR), TaxRate.ofPercent("19")));
  }

  @Test
  void rejectsBlankIdentifierOrItemName() {
    assertAll(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    InvoiceLine.standardRate(
                        " ", "Widgets", Quantity.of("1", "C62"), Money.of("10.00", EUR),
                        Money.zero(EUR), TaxRate.ofPercent("19"))),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    InvoiceLine.standardRate(
                        "1", " ", Quantity.of("1", "C62"), Money.of("10.00", EUR),
                        Money.zero(EUR), TaxRate.ofPercent("19"))));
  }
}
