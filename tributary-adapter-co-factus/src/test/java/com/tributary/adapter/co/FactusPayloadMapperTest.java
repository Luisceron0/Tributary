package com.tributary.adapter.co;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tributary.domain.Buyer;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-303: Invoice -&gt; Factus {@code /v2/bills/validate} payload. Field names and shape confirmed
 * live against the real sandbox this session (numbering_range_id 389 = "Factura de Venta"), then
 * cross-checked against an official Factus documentation artifact the user supplied — this test
 * asserts against that same confirmed shape, field by field, per T-303's own literal criterion.
 */
class FactusPayloadMapperTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  private static final FactusPayloadMapper MAPPER = new FactusPayloadMapper(389);

  @Test
  @DisplayName("RC-1: root-level fields match the confirmed Factus request shape")
  void rc1RootLevelFields() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    JsonNode payload = MAPPER.toPayload(invoice);

    assertEquals("biz-key-1", payload.get("reference_code").asText());
    assertEquals(389, payload.get("numbering_range_id").asInt());
    assertTrue(payload.has("payment_details"));
    assertTrue(payload.has("customer"));
    assertTrue(payload.has("items"));
  }

  @Test
  @DisplayName("RC-1: a B2B buyer with a tax id maps to legal_organization_code 1 (company)")
  void rc1BuyerWithTaxIdIsACompany() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    JsonNode customer = MAPPER.toPayload(invoice).get("customer");

    assertEquals("DE123456789", customer.get("identification").asText());
    assertEquals("1", customer.get("legal_organization_code").asText());
    assertEquals("Handel GmbH", customer.get("company").asText());
    assertEquals("DE", customer.get("country_code").asText());
    assertEquals("50", customer.get("identification_document_code").asText());
    // Found against the real sandbox (T-307): Factus rejects a missing "names" with "debe ser una
    // cadena de caracteres" even when legal_organization_code=1 — sent alongside "company", not
    // instead of it, contradicting a literal reading of "names obligatorio solo si =2".
    assertEquals("Handel GmbH", customer.get("names").asText());
  }

  @Test
  @DisplayName("a buyer without a tax id falls back to the empirically-confirmed anonymous consumer shape")
  void buyerWithoutTaxIdFallsBackToConsumidorFinal() {
    Buyer anonymous = Buyer.withoutTaxIdentifier("Walk-in", "CO");
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, anonymous, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    JsonNode customer = MAPPER.toPayload(invoice).get("customer");

    // Matches exactly what the live sandbox echoed back for "222222222222" during this session.
    assertEquals("222222222222", customer.get("identification").asText());
    assertEquals("13", customer.get("identification_document_code").asText());
    assertEquals("2", customer.get("legal_organization_code").asText());
    assertEquals("Consumidor final", customer.get("names").asText());
  }

  @Test
  @DisplayName("RC-1: a standard-rate line maps quantity/price/unit/tax fields exactly")
  void rc1LineFields() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "LINE-1", "Widgets", Quantity.of("3", "C62"), Money.of("10.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    JsonNode item = MAPPER.toPayload(invoice).get("items").get(0);

    assertEquals("LINE-1", item.get("code_reference").asText());
    assertEquals("Widgets", item.get("name").asText());
    assertEquals("3.00", item.get("quantity").asText());
    assertEquals("10.00", item.get("price").asText());
    assertEquals("0.00", item.get("discount_rate").asText());
    assertEquals("94", item.get("unit_measure_code").asText());
    assertEquals("999", item.get("standard_code").asText());

    JsonNode tax = item.get("taxes").get(0);
    assertEquals("01", tax.get("code").asText());
    assertEquals("19.00", tax.get("rate").asText());
  }

  @Test
  @DisplayName("a line discount maps to discount_amount, not discount_rate — the two are mutually exclusive per Factus")
  void lineDiscountMapsToDiscountAmount() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("3", "C62"), Money.of("15.00", EUR), Money.of("5.00", EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    JsonNode item = MAPPER.toPayload(invoice).get("items").get(0);

    assertEquals("5.00", item.get("discount_amount").asText());
    assertTrue(!item.has("discount_rate"), "discount_rate and discount_amount must never both be sent");
  }

  @Test
  @DisplayName("RC-3: a reverse-charge line maps to an excluded, zero-rate tax entry")
  void rc3ReverseChargeLineIsExcluded() {
    InvoiceLine line =
        InvoiceLine.reverseCharge(
            "1", "Consulting", Quantity.of("1", "C62"), Money.of("200.00", EUR), Money.zero(EUR),
            "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");
    Invoice invoice =
        Invoice.draft("biz-key-3", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    JsonNode tax = MAPPER.toPayload(invoice).get("items").get(0).get("taxes").get(0);

    assertEquals("01", tax.get("code").asText());
    assertEquals("0.00", tax.get("rate").asText());
    assertTrue(tax.get("is_excluded").asBoolean());
  }

  @Test
  @DisplayName("payment_details defaults to one cash entry covering the exact tax-inclusive total")
  void paymentDetailsDefaultsToCashForTheFullTotal() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    JsonNode payment = MAPPER.toPayload(invoice).get("payment_details").get(0);

    assertEquals("1", payment.get("payment_form").asText());
    assertEquals("10", payment.get("payment_method_code").asText());
    assertEquals("119.00", payment.get("amount").asText());
  }

  @Test
  @DisplayName("an unmapped unit code fails loudly rather than guessing")
  void unmappedUnitCodeFailsLoudly() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "XYZ-UNKNOWN"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> MAPPER.toPayload(invoice));
  }
}
