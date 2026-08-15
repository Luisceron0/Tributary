package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link InvoiceTotals#compute} against the three reference cases fixed in {@code tasks/todo.md}
 * (RC-1, RC-2, RC-3). Per lesson L-012, a property alone ("recompute equals stored") cannot
 * discriminate a correct apportionment algorithm from a subtly wrong one that is still internally
 * consistent — these are worked examples with hand-computed expected values chosen specifically to
 * exercise HALF_UP rounding at a non-trivial digit.
 */
class InvoiceTotalsTest {

  private static final Currency EUR = Currency.getInstance("EUR");

  @Test
  @DisplayName("RC-1: one line, one rate, no discounts")
  void rc1StandardSingleLine() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));

    InvoiceTotals totals = InvoiceTotals.compute(EUR, List.of(line), Money.zero(EUR));

    assertAll(
        () -> assertEquals(Money.of("100.00", EUR), totals.sumOfLineNetAmounts()),
        () -> assertEquals(Money.of("100.00", EUR), totals.taxExclusiveAmount()),
        () -> assertEquals(Money.of("19.00", EUR), totals.taxTotal()),
        () -> assertEquals(Money.of("119.00", EUR), totals.taxInclusiveAmount()),
        () -> assertEquals(Money.of("119.00", EUR), totals.amountDueForPayment()),
        () -> assertEquals(1, totals.vatBreakdown().size()));
  }

  @Test
  @DisplayName("RC-2: three lines, two rates, a line discount and a document discount — HALF_UP proof")
  void rc2MultiRateWithDiscounts() {
    // Line A: 2 x 10.00, no discount, 19% -> net 20.00
    InvoiceLine lineA =
        InvoiceLine.standardRate(
            "1", "Consulting (day 1)", Quantity.of("2", "C62"), Money.of("10.00", EUR),
            Money.zero(EUR), TaxRate.ofPercent("19"));
    // Line B: 3 x 15.00 - 5.00 discount, 19% -> net 40.00
    InvoiceLine lineB =
        InvoiceLine.standardRate(
            "2", "Consulting (day 2)", Quantity.of("3", "C62"), Money.of("15.00", EUR),
            Money.of("5.00", EUR), TaxRate.ofPercent("19"));
    // Line C: 1 x 50.00, no discount, 7% -> net 50.00
    InvoiceLine lineC =
        InvoiceLine.standardRate(
            "3", "Printed materials", Quantity.of("1", "C62"), Money.of("50.00", EUR),
            Money.zero(EUR), TaxRate.ofPercent("7"));

    // sum of line net = 20.00 + 40.00 + 50.00 = 110.00
    // document-level allowance = 10.00, apportioned proportionally per BG-23 group:
    //   7%  group net = 50.00, share = 50/110 -> allowance = 4.55 (HALF_UP)
    //   19% group net = 60.00, LAST group absorbs the residual -> allowance = 10.00 - 4.55 = 5.45
    // taxable(7%)  = 50.00 - 4.55 = 45.45 -> tax = 45.45 x 0.07 = 3.1815 -> 3.18
    // taxable(19%) = 60.00 - 5.45 = 54.55 -> tax = 54.55 x 0.19 = 10.3645 -> 10.36
    InvoiceTotals totals =
        InvoiceTotals.compute(EUR, List.of(lineA, lineB, lineC), Money.of("10.00", EUR));

    assertAll(
        () -> assertEquals(Money.of("110.00", EUR), totals.sumOfLineNetAmounts()),
        () -> assertEquals(Money.of("10.00", EUR), totals.documentLevelAllowance()),
        () -> assertEquals(Money.of("100.00", EUR), totals.taxExclusiveAmount()),
        () -> assertEquals(Money.of("13.54", EUR), totals.taxTotal()),
        () -> assertEquals(Money.of("113.54", EUR), totals.taxInclusiveAmount()),
        () -> assertEquals(Money.of("113.54", EUR), totals.amountDueForPayment()),
        () -> assertEquals(2, totals.vatBreakdown().size()));

    VatBreakdown sevenPercent = totals.vatBreakdown().get(0);
    VatBreakdown nineteenPercent = totals.vatBreakdown().get(1);
    assertAll(
        () -> assertEquals(TaxRate.ofPercent("7"), sevenPercent.rate()),
        () -> assertEquals(Money.of("45.45", EUR), sevenPercent.taxableAmount()),
        () -> assertEquals(Money.of("3.18", EUR), sevenPercent.taxAmount()),
        () -> assertEquals(TaxRate.ofPercent("19"), nineteenPercent.rate()),
        () -> assertEquals(Money.of("54.55", EUR), nineteenPercent.taxableAmount()),
        () -> assertEquals(Money.of("10.36", EUR), nineteenPercent.taxAmount()));

    // The apportioned allowances must sum to EXACTLY the document-level allowance — this is
    // BR-CO-10's real content (sum of taxable amounts = sum of line net - document allowance),
    // and it is what the residual-absorption technique on the last group guarantees.
    Money reconstructedAllowance =
        totals.sumOfLineNetAmounts().minus(totals.taxExclusiveAmount());
    assertEquals(totals.documentLevelAllowance(), reconstructedAllowance);
  }

  @Test
  @DisplayName("RC-3: reverse charge at 0% still produces a VAT breakdown entry with the exemption reason")
  void rc3ReverseCharge() {
    InvoiceLine lineD =
        InvoiceLine.reverseCharge(
            "1", "Software licence", Quantity.of("1", "C62"), Money.of("200.00", EUR),
            Money.zero(EUR), "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");
    InvoiceLine lineE =
        InvoiceLine.reverseCharge(
            "2", "Support", Quantity.of("2", "C62"), Money.of("50.00", EUR), Money.zero(EUR),
            "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");

    InvoiceTotals totals = InvoiceTotals.compute(EUR, List.of(lineD, lineE), Money.zero(EUR));

    assertAll(
        () -> assertEquals(Money.of("300.00", EUR), totals.sumOfLineNetAmounts()),
        () -> assertEquals(Money.of("300.00", EUR), totals.taxExclusiveAmount()),
        () -> assertEquals(Money.zero(EUR), totals.taxTotal()),
        () -> assertEquals(Money.of("300.00", EUR), totals.taxInclusiveAmount()),
        () -> assertEquals(1, totals.vatBreakdown().size()));

    VatBreakdown breakdown = totals.vatBreakdown().get(0);
    assertAll(
        () -> assertEquals(TaxCategory.REVERSE_CHARGE, breakdown.category()),
        () -> assertTrue(breakdown.rate().isZero()),
        () -> assertTrue(breakdown.exemptionReason().isPresent()));
  }

  @Test
  @DisplayName(
      "three-way allowance split: proportional rounding drifts the same direction on every group,"
          + " and only residual absorption on the last group recovers the exact total")
  void threeWayAllowanceSplitAbsorbsRoundingResidualOnLastGroup() {
    // Three groups, equal net amounts of 100.00 each: each group's naive share of a 10.00
    // allowance is 10.00 x (100/300) = 3.333... -> 3.33 under HALF_UP (fractional part .333 < .5,
    // so EVERY group rounds down the same way). 3 x 3.33 = 9.99, not 10.00 — a naive per-group
    // proportional round loses a cent here. Unlike RC-2's two-group split (50/110 rounds up,
    // 60/110 rounds down — complementary, so it happens to sum exactly even without residual
    // absorption), this case cannot cancel itself out. Only explicitly absorbing the residual on
    // the last group recovers the missing cent.
    InvoiceLine group5 =
        InvoiceLine.standardRate(
            "1", "Item A", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("5"));
    InvoiceLine group10 =
        InvoiceLine.standardRate(
            "2", "Item B", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("10"));
    InvoiceLine group15 =
        InvoiceLine.standardRate(
            "3", "Item C", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("15"));

    InvoiceTotals totals =
        InvoiceTotals.compute(EUR, List.of(group5, group10, group15), Money.of("10.00", EUR));

    assertAll(
        () -> assertEquals(Money.of("300.00", EUR), totals.sumOfLineNetAmounts()),
        // The whole point: this must be EXACTLY 290.00 (300.00 - 10.00), not 290.01.
        () -> assertEquals(Money.of("290.00", EUR), totals.taxExclusiveAmount()),
        () -> assertEquals(Money.of("29.00", EUR), totals.taxTotal()),
        () -> assertEquals(Money.of("319.00", EUR), totals.taxInclusiveAmount()),
        () -> assertEquals(3, totals.vatBreakdown().size()));

    assertAll(
        () -> assertEquals(Money.of("96.67", EUR), totals.vatBreakdown().get(0).taxableAmount()),
        () -> assertEquals(Money.of("4.83", EUR), totals.vatBreakdown().get(0).taxAmount()),
        () -> assertEquals(Money.of("96.67", EUR), totals.vatBreakdown().get(1).taxableAmount()),
        () -> assertEquals(Money.of("9.67", EUR), totals.vatBreakdown().get(1).taxAmount()),
        // The last group (15%, sorted last) absorbs the residual: allowance = 10.00 - 3.33 -
        // 3.33 = 3.34, one cent more than the naive 3.33 the other two groups took.
        () -> assertEquals(Money.of("96.66", EUR), totals.vatBreakdown().get(2).taxableAmount()),
        () -> assertEquals(Money.of("14.50", EUR), totals.vatBreakdown().get(2).taxAmount()));
  }

  @Test
  @DisplayName("a document-level allowance in the wrong currency fails loudly, not silently")
  void refusesAMismatchedAllowanceCurrency() {
    Currency cop = Currency.getInstance("COP");
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));

    assertThrows(
        IllegalArgumentException.class,
        () -> InvoiceTotals.compute(EUR, List.of(line), Money.of("10.00", cop)));
  }
}
