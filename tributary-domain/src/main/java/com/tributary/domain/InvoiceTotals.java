package com.tributary.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Computed invoice totals, grouped by VAT category and rate (BG-23).
 *
 * <p>A document-level allowance (BT-107) is apportioned across VAT breakdown groups
 * proportionally to each group's share of the net line total. Groups are visited in a
 * deterministic order (by category code, then rate); every group except the last takes its
 * proportional share rounded to scale 2, and the LAST group absorbs whatever residual is left.
 * That guarantees the apportioned allowances sum to EXACTLY the document-level allowance —
 * never a cent more or less — which is what makes {@code taxExclusiveAmount} equal {@code
 * sumOfLineNetAmounts - documentLevelAllowance} exactly. This is the computation SRS 9 points to
 * as where rounding defects hide (RC-2), and a property test alone cannot tell this algorithm
 * apart from a wrong one that is still internally consistent (lesson L-012) — see
 * {@code InvoiceTotalsTest} for the worked example with hand-computed expected values.
 *
 * @param sumOfLineNetAmounts BT-106, Sum of Invoice line net amounts
 * @param documentLevelAllowance BT-107, Sum of allowances on document level. Zero if none.
 * @param taxExclusiveAmount BT-109, Invoice total amount without VAT
 * @param taxTotal BT-110, Invoice total VAT amount
 * @param taxInclusiveAmount BT-112, Invoice total amount with VAT
 * @param amountDueForPayment BT-115, Amount due for payment. Equal to {@code taxInclusiveAmount}:
 *     prepaid amounts (BT-113) are out of scope (SRS 3).
 * @param vatBreakdown BG-23, one entry per (category, rate) present on the invoice
 */
public record InvoiceTotals(
    Money sumOfLineNetAmounts,
    Money documentLevelAllowance,
    Money taxExclusiveAmount,
    Money taxTotal,
    Money taxInclusiveAmount,
    Money amountDueForPayment,
    List<VatBreakdown> vatBreakdown) {

  public InvoiceTotals {
    Objects.requireNonNull(sumOfLineNetAmounts, "sumOfLineNetAmounts must not be null");
    Objects.requireNonNull(documentLevelAllowance, "documentLevelAllowance must not be null");
    Objects.requireNonNull(taxExclusiveAmount, "taxExclusiveAmount must not be null");
    Objects.requireNonNull(taxTotal, "taxTotal must not be null");
    Objects.requireNonNull(taxInclusiveAmount, "taxInclusiveAmount must not be null");
    Objects.requireNonNull(amountDueForPayment, "amountDueForPayment must not be null");
    Objects.requireNonNull(vatBreakdown, "vatBreakdown must not be null");
    vatBreakdown = List.copyOf(vatBreakdown);
  }

  /**
   * Computes totals from raw lines and a document-level allowance. Currency consistency is
   * enforced by {@link Money} itself: a line or the allowance in a different currency than
   * {@code currency} fails inside this method with {@link IllegalArgumentException}, never
   * silently.
   */
  public static InvoiceTotals compute(
      Currency currency, List<InvoiceLine> lines, Money documentLevelAllowance) {
    Objects.requireNonNull(currency, "currency must not be null");
    Objects.requireNonNull(lines, "lines must not be null");
    Objects.requireNonNull(documentLevelAllowance, "documentLevelAllowance must not be null");

    Money sumOfLineNetAmounts =
        Money.sum(currency, lines.stream().map(InvoiceLine::netAmount).toList());

    Map<GroupKey, List<InvoiceLine>> groups =
        lines.stream()
            .collect(
                Collectors.groupingBy(
                    line -> new GroupKey(line.taxCategory(), line.taxRate()),
                    LinkedHashMap::new,
                    Collectors.toList()));

    // Deterministic order so the residual-absorbing last group is reproducible regardless of the
    // order the caller supplied the lines in.
    List<GroupKey> orderedKeys =
        groups.keySet().stream()
            .sorted(
                Comparator.comparing((GroupKey key) -> key.category().code())
                    .thenComparing(key -> key.rate().percentage()))
            .toList();

    List<VatBreakdown> breakdown = new ArrayList<>(orderedKeys.size());
    Money allowanceApportioned = Money.zero(currency);
    BigDecimal totalNet = sumOfLineNetAmounts.amount();

    for (int i = 0; i < orderedKeys.size(); i++) {
      GroupKey key = orderedKeys.get(i);
      List<InvoiceLine> groupLines = groups.get(key);
      Money groupNet = Money.sum(currency, groupLines.stream().map(InvoiceLine::netAmount).toList());

      Money groupAllowance;
      if (i == orderedKeys.size() - 1) {
        groupAllowance = documentLevelAllowance.minus(allowanceApportioned);
      } else {
        BigDecimal proportion =
            totalNet.signum() == 0
                ? BigDecimal.ZERO
                : groupNet.amount().divide(totalNet, MathContext.DECIMAL64);
        groupAllowance = documentLevelAllowance.times(proportion);
        allowanceApportioned = allowanceApportioned.plus(groupAllowance);
      }

      Money taxableAmount = groupNet.minus(groupAllowance);
      Money taxAmount = key.rate().taxOn(taxableAmount);
      Optional<String> exemptionReason =
          groupLines.stream()
              .map(InvoiceLine::vatExemptionReason)
              .filter(Optional::isPresent)
              .map(Optional::get)
              .findFirst();

      breakdown.add(
          new VatBreakdown(key.category(), key.rate(), taxableAmount, taxAmount, exemptionReason));
    }

    Money taxExclusiveAmount =
        Money.sum(currency, breakdown.stream().map(VatBreakdown::taxableAmount).toList());
    Money taxTotal = Money.sum(currency, breakdown.stream().map(VatBreakdown::taxAmount).toList());
    Money taxInclusiveAmount = taxExclusiveAmount.plus(taxTotal);
    Money amountDueForPayment = taxInclusiveAmount;

    return new InvoiceTotals(
        sumOfLineNetAmounts, documentLevelAllowance, taxExclusiveAmount, taxTotal,
        taxInclusiveAmount, amountDueForPayment, breakdown);
  }

  private record GroupKey(TaxCategory category, TaxRate rate) {}
}
