package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EN16931BusinessRules}: the subset of {@code BR-*} rules the reference cases RC-1/RC-2/
 * RC-3 require (see {@code tasks/todo.md}), and nothing wider — a fourth rule is added when a
 * fourth case needs it, not speculatively.
 */
class EN16931BusinessRulesTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER_WITH_VAT_ID =
      Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
  private static final Buyer BUYER_WITHOUT_VAT_ID = Buyer.withoutTaxIdentifier("Handel GmbH", "DE");
  private static final String EXEMPTION_REASON =
      "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC";

  private static Invoice invoiceWithLines(Buyer buyer, List<InvoiceLine> lines) {
    return Invoice.draft(
        "biz-key-1", ISSUER, buyer, EUR, LocalDate.of(2026, 8, 15), lines, Money.zero(EUR));
  }

  @Test
  @DisplayName("RC-1: a clean standard-rate invoice has no violations")
  void rc1HasNoViolations() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice = invoiceWithLines(BUYER_WITH_VAT_ID, List.of(line));

    assertTrue(EN16931BusinessRules.validate(invoice).isEmpty());
  }

  @Test
  @DisplayName("RC-3: a well-formed reverse-charge invoice has no violations")
  void rc3HasNoViolations() {
    InvoiceLine line =
        InvoiceLine.reverseCharge(
            "1", "Software licence", Quantity.of("1", "C62"), Money.of("200.00", EUR),
            Money.zero(EUR), EXEMPTION_REASON);
    Invoice invoice = invoiceWithLines(BUYER_WITH_VAT_ID, List.of(line));

    assertTrue(EN16931BusinessRules.validate(invoice).isEmpty());
  }

  @Test
  @DisplayName("BR-AE-01: a reverse-charge line without the buyer's VAT identifier is rejected")
  void reverseChargeRequiresBuyerVatIdentifier() {
    InvoiceLine line =
        InvoiceLine.reverseCharge(
            "1", "Software licence", Quantity.of("1", "C62"), Money.of("200.00", EUR),
            Money.zero(EUR), EXEMPTION_REASON);
    Invoice invoice = invoiceWithLines(BUYER_WITHOUT_VAT_ID, List.of(line));

    List<RuleViolation> violations = EN16931BusinessRules.validate(invoice);
    assertTrue(violations.stream().anyMatch(v -> v.ruleId().equals("BR-AE-01")));
  }

  @Test
  @DisplayName("BR-AE-08: a reverse-charge line with a non-zero rate is rejected")
  void reverseChargeRequiresZeroRate() {
    // Bypasses the reverseCharge() factory on purpose: the factory always sets rate zero, so the
    // only way to construct the inconsistent state this rule guards against is through the
    // canonical constructor directly.
    InvoiceLine inconsistentLine =
        new InvoiceLine(
            "1", "Software licence", Quantity.of("1", "C62"), Money.of("200.00", EUR),
            Money.zero(EUR), TaxCategory.REVERSE_CHARGE, TaxRate.ofPercent("19"),
            Optional.of(EXEMPTION_REASON));
    Invoice invoice = invoiceWithLines(BUYER_WITH_VAT_ID, List.of(inconsistentLine));

    List<RuleViolation> violations = EN16931BusinessRules.validate(invoice);
    assertTrue(violations.stream().anyMatch(v -> v.ruleId().equals("BR-AE-08")));
  }

  @Test
  @DisplayName("BR-AE-10: a reverse-charge line without an exemption reason is rejected")
  void reverseChargeRequiresExemptionReason() {
    InvoiceLine inconsistentLine =
        new InvoiceLine(
            "1", "Software licence", Quantity.of("1", "C62"), Money.of("200.00", EUR),
            Money.zero(EUR), TaxCategory.REVERSE_CHARGE, TaxRate.zero(), Optional.empty());
    Invoice invoice = invoiceWithLines(BUYER_WITH_VAT_ID, List.of(inconsistentLine));

    List<RuleViolation> violations = EN16931BusinessRules.validate(invoice);
    assertTrue(violations.stream().anyMatch(v -> v.ruleId().equals("BR-AE-10")));
  }

  @Test
  @DisplayName("BR-S-01: a standard-rate line must not carry an exemption reason")
  void standardRateMustNotCarryExemptionReason() {
    InvoiceLine inconsistentLine =
        new InvoiceLine(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxCategory.STANDARD, TaxRate.ofPercent("19"), Optional.of("not applicable here"));
    Invoice invoice = invoiceWithLines(BUYER_WITH_VAT_ID, List.of(inconsistentLine));

    List<RuleViolation> violations = EN16931BusinessRules.validate(invoice);
    assertTrue(violations.stream().anyMatch(v -> v.ruleId().equals("BR-S-01")));
  }

  @Test
  @DisplayName("validate() collects every violation rather than stopping at the first")
  void collectsAllViolations() {
    InvoiceLine inconsistentLine =
        new InvoiceLine(
            "1", "Software licence", Quantity.of("1", "C62"), Money.of("200.00", EUR),
            Money.zero(EUR), TaxCategory.REVERSE_CHARGE, TaxRate.ofPercent("19"), Optional.empty());
    // Missing buyer VAT id (BR-AE-01) AND a non-zero rate (BR-AE-08) AND no exemption reason
    // (BR-AE-10) on the same line — three independent violations from one document.
    Invoice invoice = invoiceWithLines(BUYER_WITHOUT_VAT_ID, List.of(inconsistentLine));

    List<RuleViolation> violations = EN16931BusinessRules.validate(invoice);
    assertEquals(
        java.util.Set.of("BR-AE-01", "BR-AE-08", "BR-AE-10"),
        violations.stream().map(RuleViolation::ruleId).collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  @DisplayName("BR-CO-10: a claimed tax-exclusive amount inconsistent with the lines is rejected")
  void netAmountConsistencyCheckDetectsAMismatch() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));

    // Consistent: 100.00 net, zero allowance -> tax-exclusive must be 100.00.
    assertTrue(
        EN16931BusinessRules.checkNetAmountConsistency(
                EUR, List.of(line), Money.zero(EUR), Money.of("100.00", EUR))
            .isEmpty());

    // Deliberately wrong claim, fed directly to the rule rather than through Invoice.draft() —
    // Invoice always computes a consistent total, so this is the only way to exercise the branch
    // RF-005 requires ("rejected before serializing"). See lesson L-012: a property that always
    // holds for the one construction path proves nothing about the rule in isolation.
    Optional<RuleViolation> violation =
        EN16931BusinessRules.checkNetAmountConsistency(
            EUR, List.of(line), Money.zero(EUR), Money.of("99.00", EUR));
    assertTrue(violation.isPresent());
    assertEquals("BR-CO-10", violation.orElseThrow().ruleId());
  }

  @Test
  @DisplayName("BR-CO-10 accounts for the document-level allowance, not just the line sum")
  void netAmountConsistencyCheckAccountsForDocumentAllowance() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));

    assertFalse(
        EN16931BusinessRules.checkNetAmountConsistency(
                EUR, List.of(line), Money.of("10.00", EUR), Money.of("90.00", EUR))
            .isPresent());
    assertTrue(
        EN16931BusinessRules.checkNetAmountConsistency(
                EUR, List.of(line), Money.of("10.00", EUR), Money.of("100.00", EUR))
            .isPresent());
  }
}
