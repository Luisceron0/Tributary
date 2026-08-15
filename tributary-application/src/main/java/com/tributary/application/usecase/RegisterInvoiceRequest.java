package com.tributary.application.usecase;

import com.tributary.domain.Buyer;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * RF-001's request: "header, buyer, lines and payment information." Payment information is not
 * modelled here — nothing in scope (RC-1/RC-2/RC-3, no {@code BR-*} rule, no adapter) reads it yet,
 * and SRS 6.4 has no payment entity either; adding one speculatively would be scope the SRS didn't
 * ask for.
 *
 * @param saleId the caller's own external identifier for the sale — see {@link BusinessKey}
 * @param issuer BG-4. RF-001's precondition ("an issuer is configured") is resolved by the caller;
 *     this use case does not look one up
 * @param buyer BG-7
 * @param currency BT-5
 * @param issueDate BT-2
 * @param lines BG-25
 * @param documentLevelAllowance BT-107, zero if none
 */
public record RegisterInvoiceRequest(
    String saleId,
    Issuer issuer,
    Buyer buyer,
    Currency currency,
    LocalDate issueDate,
    List<InvoiceLine> lines,
    Money documentLevelAllowance) {

  public RegisterInvoiceRequest {
    Objects.requireNonNull(saleId, "saleId must not be null");
    if (saleId.isBlank()) {
      throw new IllegalArgumentException("saleId must not be blank");
    }
    Objects.requireNonNull(issuer, "issuer must not be null");
    Objects.requireNonNull(buyer, "buyer must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    Objects.requireNonNull(issueDate, "issueDate must not be null");
    Objects.requireNonNull(lines, "lines must not be null");
    lines = List.copyOf(lines);
    Objects.requireNonNull(documentLevelAllowance, "documentLevelAllowance must not be null");
  }
}
