package com.tributary.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount in a single currency, always held at scale 2 with {@link RoundingMode#HALF_UP}.
 *
 * <p>Normalisation happens in the canonical constructor, so there is no way to obtain a {@code
 * Money} at another scale — not through a factory, not through arithmetic, not through {@code new}.
 * That matters for equality: {@code BigDecimal.equals} is scale-sensitive, so {@code 10.0} and
 * {@code 10.00} would otherwise be different values, and a record's generated {@code equals}
 * delegates straight to it.
 *
 * <p>{@code double} and {@code float} appear nowhere in this class and must appear nowhere in this
 * module. Binary floating point cannot represent 0.10, and a tax base that drifts by a cent is a
 * defect regardless of what the tests say.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

  /** Every monetary value in the system is held at this scale. */
  public static final int SCALE = 2;

  /** The rounding mode mandated for all money and tax arithmetic. */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  public Money {
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    amount = amount.setScale(SCALE, ROUNDING);
  }

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  /**
   * @param amount decimal text, e.g. {@code "1234.56"}. Text avoids the {@code double} literal that
   *     would silently lose precision before this class ever sees the value.
   */
  public static Money of(String amount, Currency currency) {
    return new Money(new BigDecimal(Objects.requireNonNull(amount, "amount must not be null")), currency);
  }

  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  /**
   * Sums a collection in a stated currency. The currency is a parameter rather than something
   * inferred from the first element, so that summing an empty collection still yields a typed zero
   * instead of failing or guessing.
   */
  public static Money sum(Currency currency, Collection<Money> values) {
    Objects.requireNonNull(currency, "currency must not be null");
    Objects.requireNonNull(values, "values must not be null");
    BigDecimal total = BigDecimal.ZERO;
    for (Money value : values) {
      requireSameCurrency(currency, value);
      total = total.add(value.amount);
    }
    return new Money(total, currency);
  }

  public Money plus(Money other) {
    requireSameCurrency(currency, other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(currency, other);
    return new Money(amount.subtract(other.amount), currency);
  }

  /** Multiplies by a dimensionless factor, rounding the result back to scale 2. */
  public Money times(BigDecimal factor) {
    Objects.requireNonNull(factor, "factor must not be null");
    return new Money(amount.multiply(factor), currency);
  }

  public Money negated() {
    return new Money(amount.negate(), currency);
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  @Override
  public int compareTo(Money other) {
    requireSameCurrency(currency, other);
    return amount.compareTo(other.amount);
  }

  @Override
  public String toString() {
    return amount.toPlainString() + " " + currency.getCurrencyCode();
  }

  private static void requireSameCurrency(Currency expected, Money value) {
    Objects.requireNonNull(value, "money must not be null");
    if (!expected.equals(value.currency)) {
      throw new IllegalArgumentException(
          "cannot combine %s with %s: mixing currencies is never an implicit conversion"
              .formatted(expected.getCurrencyCode(), value.currency.getCurrencyCode()));
    }
  }
}
