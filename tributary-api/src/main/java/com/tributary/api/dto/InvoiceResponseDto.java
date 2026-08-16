package com.tributary.api.dto;

import com.tributary.domain.Invoice;
import java.math.BigDecimal;
import java.time.LocalDate;

/** What {@code GET /api/v1/invoices/{id}} and every invoice-mutating endpoint return. */
public record InvoiceResponseDto(
    String businessKey, String state, String issuerTaxIdentifier, String buyerName, String currency,
    LocalDate issueDate, BigDecimal taxExclusiveAmount, BigDecimal taxTotal, BigDecimal taxInclusiveAmount) {

  public static InvoiceResponseDto from(Invoice invoice) {
    return new InvoiceResponseDto(
        invoice.businessKey(),
        invoice.state().name(),
        invoice.issuer().taxIdentifier(),
        invoice.buyer().name(),
        invoice.currency().getCurrencyCode(),
        invoice.issueDate(),
        invoice.totals().taxExclusiveAmount().amount(),
        invoice.totals().taxTotal().amount(),
        invoice.totals().taxInclusiveAmount().amount());
  }
}
