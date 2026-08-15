package com.tributary.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * One VAT breakdown group (BG-23): every invoice line sharing a VAT category and rate, aggregated.
 *
 * @param category BT-118, VAT category code
 * @param rate BT-119, VAT category rate
 * @param taxableAmount BT-116, VAT category taxable amount — this group's share of the net total,
 *     after apportioning any document-level allowance (BT-107) proportionally
 * @param taxAmount BT-117, VAT category tax amount
 * @param exemptionReason BT-120, VAT exemption reason text — present when {@code category} is
 *     {@link TaxCategory#REVERSE_CHARGE}
 */
public record VatBreakdown(
    TaxCategory category,
    TaxRate rate,
    Money taxableAmount,
    Money taxAmount,
    Optional<String> exemptionReason) {

  public VatBreakdown {
    Objects.requireNonNull(category, "category must not be null");
    Objects.requireNonNull(rate, "rate must not be null");
    Objects.requireNonNull(taxableAmount, "taxableAmount must not be null");
    Objects.requireNonNull(taxAmount, "taxAmount must not be null");
    Objects.requireNonNull(
        exemptionReason, "exemptionReason must not be null — use Optional.empty()");
  }
}
