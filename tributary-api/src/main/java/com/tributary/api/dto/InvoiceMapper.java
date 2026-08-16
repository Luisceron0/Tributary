package com.tributary.api.dto;

import com.tributary.application.usecase.RegisterInvoiceRequest;
import com.tributary.domain.Buyer;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxCategory;
import com.tributary.domain.TaxRate;
import java.util.Currency;
import java.util.Optional;

/**
 * DTO -&gt; domain, the one place JSON structure and EN 16931 vocabulary meet. Never the reverse
 * direction inside a use case — {@code tributary-application} does not know this class exists
 * (ADR-001's dependency direction: adapters, including this HTTP one, depend inward).
 */
public final class InvoiceMapper {

  private InvoiceMapper() {}

  public static RegisterInvoiceRequest toRegisterRequest(InvoiceRequestDto dto) {
    Currency currency = Currency.getInstance(dto.currency());
    return new RegisterInvoiceRequest(
        dto.saleId(),
        toIssuer(dto.issuer()),
        toBuyer(dto.buyer()),
        currency,
        dto.issueDate(),
        dto.lines().stream().map(line -> toInvoiceLine(line, currency)).toList(),
        dto.documentLevelAllowance() == null ? Money.zero(currency) : Money.of(dto.documentLevelAllowance(), currency));
  }

  private static Issuer toIssuer(InvoiceRequestDto.IssuerDto dto) {
    return new Issuer(dto.name(), dto.taxIdentifier(), dto.countryCode());
  }

  private static Buyer toBuyer(InvoiceRequestDto.BuyerDto dto) {
    Buyer base =
        (dto.taxIdentifier() == null || dto.taxIdentifier().isBlank())
            ? Buyer.withoutTaxIdentifier(dto.name(), dto.countryCode())
            : Buyer.withTaxIdentifier(dto.name(), dto.taxIdentifier(), dto.countryCode());
    return base.withPersonalData(
        Optional.ofNullable(dto.address()), Optional.ofNullable(dto.email()), Optional.ofNullable(dto.phone()));
  }

  private static InvoiceLine toInvoiceLine(InvoiceRequestDto.InvoiceLineDto dto, Currency currency) {
    Quantity quantity = Quantity.of(dto.quantity(), dto.unitCode());
    Money unitPrice = Money.of(dto.unitPrice(), currency);
    Money lineDiscount = dto.lineDiscount() == null ? Money.zero(currency) : Money.of(dto.lineDiscount(), currency);

    TaxCategory category = TaxCategory.valueOf(dto.taxCategory());
    if (category == TaxCategory.REVERSE_CHARGE) {
      String reason =
          dto.vatExemptionReason() == null
              ? ""
              : dto.vatExemptionReason(); // domain rejects a missing reason via BR-AE-10, not this layer
      return InvoiceLine.reverseCharge(dto.lineIdentifier(), dto.itemName(), quantity, unitPrice, lineDiscount, reason);
    }
    TaxRate rate = TaxRate.of(dto.taxRate());
    return InvoiceLine.standardRate(dto.lineIdentifier(), dto.itemName(), quantity, unitPrice, lineDiscount, rate);
  }
}
