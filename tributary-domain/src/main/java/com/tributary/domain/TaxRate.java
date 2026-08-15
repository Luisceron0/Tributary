package com.tributary.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A tax rate expressed as a percentage, held at scale 2 — 19.00, 7.00, 0.00.
 *
 * <p>The rate is stored as a percentage rather than a fraction because that is how EN 16931 states
 * it (BT-119, VAT category rate) and how every regime in scope reports it. Converting to a fraction
 * at the point of use keeps the stored value identical to the one that appears on the document, so
 * a rate never has to be reconstructed from a rounded fraction.
 *
 * <p>A zero rate is a legitimate business case, not a missing value: it is what RC-3 uses for an
 * intra-community supply under reverse charge, where the exemption reason carries the meaning.
 */
public record TaxRate(BigDecimal percentage) implements Comparable<TaxRate> {

  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  public TaxRate {
    Objects.requireNonNull(percentage, "percentage must not be null");
    percentage = percentage.setScale(Money.SCALE, Money.ROUNDING);
    if (percentage.signum() < 0) {
      throw new IllegalArgumentException("tax rate must not be negative: " + percentage);
    }
    if (percentage.compareTo(ONE_HUNDRED) > 0) {
      throw new IllegalArgumentException("tax rate must not exceed 100%: " + percentage);
    }
  }

  public static TaxRate of(BigDecimal percentage) {
    return new TaxRate(percentage);
  }

  /**
   * @param percentage decimal text, e.g. {@code "19"} or {@code "19.00"}
   */
  public static TaxRate ofPercent(String percentage) {
    return new TaxRate(
        new BigDecimal(Objects.requireNonNull(percentage, "percentage must not be null")));
  }

  public static TaxRate zero() {
    return new TaxRate(BigDecimal.ZERO);
  }

  /**
   * Applies this rate to a taxable base, rounding the result to scale 2.
   *
   * <p>Note what this method does NOT do: it does not decide which base to apply itself to. Under
   * EN 16931 the tax of a document is computed per VAT breakdown group (BG-23) on the summed
   * taxable amount of that group, not by summing per-line tax. Applying this per line and adding up
   * gives a different total, and that difference is exactly the rounding defect RC-2 exists to
   * catch. Choosing the base is the caller's job, and the business rules of T-104 constrain it.
   */
  public Money taxOn(Money base) {
    Objects.requireNonNull(base, "base must not be null");
    return base.times(percentage.divide(ONE_HUNDRED));
  }

  public boolean isZero() {
    return percentage.signum() == 0;
  }

  @Override
  public int compareTo(TaxRate other) {
    return percentage.compareTo(other.percentage);
  }

  @Override
  public String toString() {
    return percentage.toPlainString() + "%";
  }
}
