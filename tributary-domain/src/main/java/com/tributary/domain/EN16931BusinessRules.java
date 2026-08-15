package com.tributary.domain;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The subset of EN 16931 {@code BR-*} business rules that the reference cases RC-1/RC-2/RC-3
 * require (see {@code tasks/todo.md}). Adding a rule is adding a case to those scenarios first,
 * not a speculative extension.
 *
 * <p><b>Important disambiguation:</b> {@code BR-CO-*} in EN 16931 is the "calculation"
 * (<i>CO</i>ndition) rule family — it has nothing to do with the Colombian regime. BR-CO-10 is
 * "the invoice total amount without VAT equals the sum of line net amounts minus document-level
 * allowances plus document-level charges." The collision with the {@code CO} country/regime code
 * used elsewhere in this system (Colombia, {@code tributary-adapter-co-factus}) is coincidental
 * and worth remembering before reaching for the wrong adapter.
 *
 * <p>All checks are collected, never fail-fast: RF-001 requires "422 with the complete list of
 * violations," not the first one found.
 */
public final class EN16931BusinessRules {

  private EN16931BusinessRules() {}

  public static List<RuleViolation> validate(Invoice invoice) {
    Objects.requireNonNull(invoice, "invoice must not be null");
    List<RuleViolation> violations = new ArrayList<>();

    checkReverseChargeRequiresBuyerVatIdentifier(invoice, violations);
    checkLineCategoryConsistency(invoice, violations);
    checkNetAmountConsistency(
            invoice.currency(), invoice.lines(), invoice.documentLevelAllowance(),
            invoice.totals().taxExclusiveAmount())
        .ifPresent(violations::add);

    return List.copyOf(violations);
  }

  /** BR-AE-01: a reverse-charge line requires the buyer's VAT identifier (BT-48). */
  private static void checkReverseChargeRequiresBuyerVatIdentifier(
      Invoice invoice, List<RuleViolation> violations) {
    boolean hasReverseCharge =
        invoice.lines().stream().anyMatch(line -> line.taxCategory() == TaxCategory.REVERSE_CHARGE);
    if (hasReverseCharge && invoice.buyer().taxIdentifier().isEmpty()) {
      violations.add(
          new RuleViolation(
              "BR-AE-01", "a reverse-charge line requires the buyer's VAT identifier (BT-48)"));
    }
  }

  /**
   * BR-AE-08: a reverse-charge line must carry a zero VAT rate. BR-AE-10: a reverse-charge line
   * requires an exemption reason (BT-120). BR-S-01: a standard-rate line must not carry one — the
   * symmetric case, so the field never means "irrelevant text" on a taxed line.
   */
  private static void checkLineCategoryConsistency(Invoice invoice, List<RuleViolation> violations) {
    for (InvoiceLine line : invoice.lines()) {
      switch (line.taxCategory()) {
        case REVERSE_CHARGE -> {
          if (!line.taxRate().isZero()) {
            violations.add(
                new RuleViolation(
                    "BR-AE-08",
                    "line %s: a reverse-charge line must have a zero VAT rate, was %s"
                        .formatted(line.lineIdentifier(), line.taxRate())));
          }
          if (line.vatExemptionReason().isEmpty()) {
            violations.add(
                new RuleViolation(
                    "BR-AE-10",
                    "line %s: a reverse-charge line requires a VAT exemption reason (BT-120)"
                        .formatted(line.lineIdentifier())));
          }
        }
        case STANDARD -> {
          if (line.vatExemptionReason().isPresent()) {
            violations.add(
                new RuleViolation(
                    "BR-S-01",
                    "line %s: a standard-rate line must not carry a VAT exemption reason"
                        .formatted(line.lineIdentifier())));
          }
        }
      }
    }
  }

  /**
   * BR-CO-10: the invoice total amount without VAT (BT-109) must equal the sum of line net
   * amounts (BT-106) minus the document-level allowance (BT-107).
   *
   * <p>Exposed standalone, taking raw components rather than an {@link Invoice}, so it can be
   * exercised directly with a deliberately inconsistent claimed amount. Through {@link
   * #validate(Invoice)} this can never actually fire today: {@link Invoice#draft} is the only way
   * to build an {@code Invoice}, and it always computes {@code taxExclusiveAmount} with this same
   * formula (see {@link InvoiceTotals#compute}). It stays wired in anyway, the same way ADR-002
   * has PostgreSQL re-verify a hash the application already computed correctly: a second
   * computation that independently checks the first is what keeps a future second construction
   * path (persistence reconstructing an {@code Invoice} from a stored row, for instance) from
   * silently trusting a total that has drifted from its lines.
   */
  static Optional<RuleViolation> checkNetAmountConsistency(
      Currency currency,
      List<InvoiceLine> lines,
      Money documentLevelAllowance,
      Money claimedTaxExclusiveAmount) {
    Money expected =
        Money.sum(currency, lines.stream().map(InvoiceLine::netAmount).toList())
            .minus(documentLevelAllowance);
    if (!expected.equals(claimedTaxExclusiveAmount)) {
      return Optional.of(
          new RuleViolation(
              "BR-CO-10",
              "tax-exclusive amount %s does not equal sum of line net amounts minus document allowance (%s)"
                  .formatted(claimedTaxExclusiveAmount, expected)));
    }
    return Optional.empty();
  }
}
