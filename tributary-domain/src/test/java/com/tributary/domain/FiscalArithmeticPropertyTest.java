package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the fiscal arithmetic — the verification criterion of T-100.
 *
 * <p>SRS 9 puts it plainly: this is where the rounding errors that an example test cannot find
 * show up. Examples pin the cases someone thought of; these properties assert the laws that must
 * hold for every combination of lines, rates and amounts.
 */
class FiscalArithmeticPropertyTest {

  private static final Currency EUR = Currency.getInstance("EUR");

  @Provide
  Arbitrary<Money> amounts() {
    return Arbitraries.bigDecimals()
        .between(new BigDecimal("-1000000"), new BigDecimal("1000000"))
        .ofScale(6)
        .map(value -> Money.of(value, EUR));
  }

  @Provide
  Arbitrary<Money> nonNegativeAmounts() {
    return Arbitraries.bigDecimals()
        .between(BigDecimal.ZERO, new BigDecimal("1000000"))
        .ofScale(6)
        .map(value -> Money.of(value, EUR));
  }

  @Provide
  Arbitrary<List<Money>> lineAmounts() {
    return nonNegativeAmounts().list().ofMinSize(1).ofMaxSize(40);
  }

  @Provide
  Arbitrary<TaxRate> rates() {
    return Arbitraries.bigDecimals()
        .between(BigDecimal.ZERO, new BigDecimal("100"))
        .ofScale(2)
        .map(TaxRate::of);
  }

  // --- The criterion of T-100 -------------------------------------------------------------

  @Property
  void basePlusTaxEqualsTotalAtScaleTwo(
      @ForAll("nonNegativeAmounts") Money base, @ForAll("rates") TaxRate rate) {
    Money tax = rate.taxOn(base);
    Money total = base.plus(tax);

    assertEquals(2, base.amount().scale(), "base scale");
    assertEquals(2, tax.amount().scale(), "tax scale");
    assertEquals(2, total.amount().scale(), "total scale");
    assertEquals(
        base.amount().add(tax.amount()),
        total.amount(),
        "base + tax must equal the total exactly, with no drift");
  }

  @Property
  void aSumOfLinesEqualsTheSumOfItsParts(
      @ForAll("lineAmounts") List<Money> lines, @ForAll("rates") TaxRate rate) {
    Money netTotal = Money.sum(EUR, lines);
    Money taxTotal = rate.taxOn(netTotal);
    Money grandTotal = netTotal.plus(taxTotal);

    BigDecimal expectedNet =
        lines.stream().map(Money::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

    assertEquals(expectedNet.setScale(2, RoundingMode.HALF_UP), netTotal.amount());
    assertEquals(2, grandTotal.amount().scale());
    assertEquals(netTotal.amount().add(taxTotal.amount()), grandTotal.amount());
  }

  // --- Laws the value object must never break --------------------------------------------

  @Property
  void everyMoneyIsScaleTwo(@ForAll("amounts") Money money) {
    assertEquals(2, money.amount().scale());
  }

  @Property
  void additionIsCommutative(@ForAll("amounts") Money a, @ForAll("amounts") Money b) {
    assertEquals(a.plus(b), b.plus(a));
  }

  @Property
  void additionIsAssociative(
      @ForAll("amounts") Money a, @ForAll("amounts") Money b, @ForAll("amounts") Money c) {
    assertEquals(a.plus(b).plus(c), a.plus(b.plus(c)));
  }

  @Property
  void zeroIsTheAdditiveIdentity(@ForAll("amounts") Money a) {
    assertEquals(a, a.plus(Money.zero(EUR)));
  }

  @Property
  void subtractionUndoesAddition(@ForAll("amounts") Money a, @ForAll("amounts") Money b) {
    assertEquals(a, a.plus(b).minus(b));
  }

  @Property
  void taxIsNeverNegativeForANonNegativeBase(
      @ForAll("nonNegativeAmounts") Money base, @ForAll("rates") TaxRate rate) {
    assertTrue(
        rate.taxOn(base).amount().signum() >= 0,
        () -> "tax on a non-negative base must not be negative: " + rate + " on " + base);
  }

  @Property
  void aZeroRateAlwaysProducesZeroTax(@ForAll("amounts") Money base) {
    assertEquals(Money.zero(EUR), TaxRate.zero().taxOn(base));
  }

  @Property
  void taxNeverExceedsTheBaseForRatesUpToOneHundredPercent(
      @ForAll("nonNegativeAmounts") Money base, @ForAll("rates") TaxRate rate) {
    assertTrue(
        rate.taxOn(base).amount().compareTo(base.amount()) <= 0,
        () -> "tax " + rate.taxOn(base) + " exceeded base " + base + " at rate " + rate);
  }
}
