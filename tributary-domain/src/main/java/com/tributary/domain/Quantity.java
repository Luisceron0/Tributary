package com.tributary.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An invoiced quantity (BT-129) with its unit of measure code (BT-130).
 *
 * <p>Quantity is deliberately NOT held at scale 2. The scale-2 rule exists to make monetary values
 * exact and to match how every regime in scope reports amounts; a quantity is neither money nor
 * tax. Forcing 0.125 hours to 0.13 would corrupt {@code quantity x unit price} before any rounding
 * decision is taken, and the resulting cent of drift would then be indistinguishable from a
 * genuine rounding defect.
 *
 * <p>The scale here is 6, which covers the fractional units that appear in practice (hours, mass,
 * volume) without pretending to arbitrary precision. Rounding to money happens once, at the point
 * where a line amount is computed — and there it is scale 2 HALF_UP like everything else.
 */
public record Quantity(BigDecimal value, String unitCode) implements Comparable<Quantity> {

  /** Fractional precision retained for physical amounts before they become money. */
  public static final int SCALE = 6;

  public Quantity {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(unitCode, "unitCode must not be null");
    if (unitCode.isBlank()) {
      throw new IllegalArgumentException("unitCode must not be blank");
    }
    value = value.setScale(SCALE, Money.ROUNDING);
    if (value.signum() < 0) {
      throw new IllegalArgumentException("quantity must not be negative: " + value);
    }
    unitCode = unitCode.strip();
  }

  public static Quantity of(BigDecimal value, String unitCode) {
    return new Quantity(value, unitCode);
  }

  /**
   * @param value decimal text, e.g. {@code "0.125"}
   * @param unitCode UN/ECE Recommendation 20 code, e.g. {@code "C62"} (one), {@code "HUR"} (hour)
   */
  public static Quantity of(String value, String unitCode) {
    return new Quantity(
        new BigDecimal(Objects.requireNonNull(value, "value must not be null")), unitCode);
  }

  public boolean isZero() {
    return value.signum() == 0;
  }

  /** Compares quantities of the same unit. Comparing across units is meaningless, so it is refused. */
  @Override
  public int compareTo(Quantity other) {
    Objects.requireNonNull(other, "quantity must not be null");
    if (!unitCode.equals(other.unitCode)) {
      throw new IllegalArgumentException(
          "cannot compare quantities in %s and %s".formatted(unitCode, other.unitCode));
    }
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value.toPlainString() + " " + unitCode;
  }
}
