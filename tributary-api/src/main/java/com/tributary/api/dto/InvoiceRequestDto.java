package com.tributary.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code POST /api/v1/invoices} — RF-001. Field-level {@code @NotNull}/{@code @NotBlank} catch
 * missing structure before it ever reaches domain construction; EN 16931 business rule violations
 * (BR-xx) are a completely different, later failure the domain itself reports (T-104) — this
 * layer never re-implements or duplicates those.
 */
public record InvoiceRequestDto(
    @NotBlank String saleId,
    @NotNull @Valid IssuerDto issuer,
    @NotNull @Valid BuyerDto buyer,
    @NotBlank String currency,
    @NotNull LocalDate issueDate,
    @NotNull List<@Valid InvoiceLineDto> lines,
    BigDecimal documentLevelAllowance) {

  public record IssuerDto(
      @NotBlank String name, @NotBlank String taxIdentifier, @NotBlank String countryCode) {}

  public record BuyerDto(
      @NotBlank String name,
      String taxIdentifier,
      @NotBlank String countryCode,
      String address,
      String email,
      String phone) {}

  public record InvoiceLineDto(
      @NotBlank String lineIdentifier,
      @NotBlank String itemName,
      @NotNull BigDecimal quantity,
      @NotBlank String unitCode,
      @NotNull BigDecimal unitPrice,
      BigDecimal lineDiscount,
      @NotBlank String taxCategory,
      @NotNull BigDecimal taxRate,
      String vatExemptionReason) {}
}
