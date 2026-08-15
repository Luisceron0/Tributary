package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Invoice}: the aggregate root assembling issuer, buyer, lines and computed totals. */
class InvoiceTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  @Test
  void draftComputesItsTotalsFromItsLines() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));

    Invoice invoice =
        Invoice.draft(
            "biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    assertAll(
        () -> assertEquals("biz-key-1", invoice.businessKey()),
        () -> assertEquals(DocumentState.DRAFT, invoice.state()),
        () -> assertEquals(ISSUER, invoice.issuer()),
        () -> assertEquals(BUYER, invoice.buyer()),
        () -> assertEquals(Money.of("119.00", EUR), invoice.totals().taxInclusiveAmount()));
  }

  @Test
  void transitionToProducesANewInvoiceRatherThanMutatingTheOriginal() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(
            "biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    Invoice submitting = draft.transitionTo(DocumentState.SUBMITTING);

    assertAll(
        () -> assertEquals(DocumentState.DRAFT, draft.state(), "the original is never mutated"),
        () -> assertEquals(DocumentState.SUBMITTING, submitting.state()),
        () -> assertEquals(draft.businessKey(), submitting.businessKey()),
        () -> assertEquals(draft.totals(), submitting.totals()));
  }

  @Test
  void transitionToRejectsAnUndeclaredTransition() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(
            "biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    assertThrows(IllegalStateException.class, () -> draft.transitionTo(DocumentState.ISSUED));
  }

  @Test
  void rejectsAnInvoiceWithNoLines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Invoice.draft(
                "biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(),
                Money.zero(EUR)));
  }

  @Test
  void rejectsABlankBusinessKey() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Invoice.draft(
                "  ", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR)));
  }

  @Test
  void linesAreDefensivelyCopied() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    java.util.List<InvoiceLine> mutable = new java.util.ArrayList<>(List.of(line));

    Invoice invoice =
        Invoice.draft(
            "biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), mutable, Money.zero(EUR));
    mutable.clear();

    assertEquals(1, invoice.lines().size());
  }
}
