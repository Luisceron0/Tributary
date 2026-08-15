package com.tributary.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * An invoice line (BG-25).
 *
 * @param lineIdentifier BT-126, Invoice line identifier
 * @param itemName BT-153, Item name
 * @param quantity BT-129/BT-130, Invoiced quantity and its unit of measure
 * @param unitPrice BT-146, Item net price
 * @param lineDiscount BT-136 (part of BG-27), amount of an allowance at line level. Zero if none.
 * @param taxCategory BT-151, Invoiced item VAT category code
 * @param taxRate BT-152, Invoiced item VAT rate
 * @param vatExemptionReason BT-120, VAT exemption reason text. Mandatory when {@code taxCategory}
 *     is {@link TaxCategory#REVERSE_CHARGE} (BR-AE-10) — enforced by {@code EN16931BusinessRules},
 *     not here.
 */
public record InvoiceLine(
    String lineIdentifier,
    String itemName,
    Quantity quantity,
    Money unitPrice,
    Money lineDiscount,
    TaxCategory taxCategory,
    TaxRate taxRate,
    Optional<String> vatExemptionReason) {

  public InvoiceLine {
    lineIdentifier = Preconditions.requireNonBlank(lineIdentifier, "lineIdentifier");
    itemName = Preconditions.requireNonBlank(itemName, "itemName");
    Objects.requireNonNull(quantity, "quantity must not be null");
    Objects.requireNonNull(unitPrice, "unitPrice must not be null");
    Objects.requireNonNull(lineDiscount, "lineDiscount must not be null");
    Objects.requireNonNull(taxCategory, "taxCategory must not be null");
    Objects.requireNonNull(taxRate, "taxRate must not be null");
    Objects.requireNonNull(
        vatExemptionReason, "vatExemptionReason must not be null — use Optional.empty()");
    if (lineDiscount.isNegative()) {
      throw new IllegalArgumentException("lineDiscount must not be negative: " + lineDiscount);
    }
  }

  public static InvoiceLine standardRate(
      String lineIdentifier,
      String itemName,
      Quantity quantity,
      Money unitPrice,
      Money lineDiscount,
      TaxRate taxRate) {
    return new InvoiceLine(
        lineIdentifier, itemName, quantity, unitPrice, lineDiscount, TaxCategory.STANDARD,
        taxRate, Optional.empty());
  }

  public static InvoiceLine reverseCharge(
      String lineIdentifier,
      String itemName,
      Quantity quantity,
      Money unitPrice,
      Money lineDiscount,
      String vatExemptionReason) {
    return new InvoiceLine(
        lineIdentifier, itemName, quantity, unitPrice, lineDiscount, TaxCategory.REVERSE_CHARGE,
        TaxRate.zero(), Optional.of(vatExemptionReason));
  }

  /**
   * BT-131: Invoice line net amount — quantity x unit price, less the line discount, rounded to
   * scale 2 HALF_UP.
   */
  public Money netAmount() {
    return unitPrice.times(quantity.value()).minus(lineDiscount);
  }
}
