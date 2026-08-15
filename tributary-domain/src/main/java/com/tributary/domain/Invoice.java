package com.tributary.domain;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The invoice, as a value at a point in time — the root of the EN 16931 model.
 *
 * <p>Immutable: nothing about an invoice is ever edited. A later revision — a state transition,
 * a correction — produces a new value; it never mutates this one. That mirrors the system-wide
 * rule that a fiscal document is corrected only by a later document that references it (SRS 1),
 * applied at the object level. The state machine of T-102 builds on this by returning a new
 * {@code Invoice} rather than flipping a field.
 *
 * @param businessKey the deterministic identity used for idempotent issuance (ADR-003). Not an
 *     EN 16931 term — it is Tributary's own idempotency design, not the standard's.
 * @param state the document's position in the {@link DocumentState} lifecycle (SRS 6.4)
 * @param issuer BG-4, Seller
 * @param buyer BG-7, Buyer
 * @param currency BT-5, Invoice currency code
 * @param issueDate BT-2, Invoice issue date
 * @param lines BG-25, Invoice lines. At least one is required: an invoice with no lines has
 *     nothing to invoice.
 * @param documentLevelAllowance BT-107, Sum of allowances on document level. Zero if none.
 * @param totals computed from {@code lines} and {@code documentLevelAllowance} — see
 *     {@link InvoiceTotals#compute}
 */
public record Invoice(
    String businessKey,
    DocumentState state,
    Issuer issuer,
    Buyer buyer,
    Currency currency,
    LocalDate issueDate,
    List<InvoiceLine> lines,
    Money documentLevelAllowance,
    InvoiceTotals totals) {

  public Invoice {
    businessKey = Preconditions.requireNonBlank(businessKey, "businessKey");
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(issuer, "issuer must not be null");
    Objects.requireNonNull(buyer, "buyer must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    Objects.requireNonNull(issueDate, "issueDate must not be null");
    Objects.requireNonNull(lines, "lines must not be null");
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("an invoice must have at least one line");
    }
    lines = List.copyOf(lines);
    Objects.requireNonNull(documentLevelAllowance, "documentLevelAllowance must not be null");
    Objects.requireNonNull(totals, "totals must not be null");
  }

  /** Builds a draft invoice in {@link DocumentState#DRAFT}, computing totals from lines and allowance. */
  public static Invoice draft(
      String businessKey,
      Issuer issuer,
      Buyer buyer,
      Currency currency,
      LocalDate issueDate,
      List<InvoiceLine> lines,
      Money documentLevelAllowance) {
    InvoiceTotals totals = InvoiceTotals.compute(currency, lines, documentLevelAllowance);
    return new Invoice(
        businessKey, DocumentState.DRAFT, issuer, buyer, currency, issueDate, lines,
        documentLevelAllowance, totals);
  }

  /**
   * Returns a NEW invoice in {@code next}, validated against {@link DocumentState}'s transition
   * table. This invoice is never mutated — see the class-level note on immutability.
   *
   * @throws IllegalStateException if {@code next} is not a declared transition from {@link
   *     #state()}
   */
  public Invoice transitionTo(DocumentState next) {
    DocumentState validated = state.transitionTo(next);
    return new Invoice(
        businessKey, validated, issuer, buyer, currency, issueDate, lines,
        documentLevelAllowance, totals);
  }
}
