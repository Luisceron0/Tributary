package com.tributary.adapter.co;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tributary.domain.Buyer;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Money;
import com.tributary.domain.TaxCategory;
import java.util.Map;

/**
 * T-303: Invoice (EN 16931) -&gt; Factus {@code /v2/bills/validate} JSON. Field names and shape
 * confirmed live against the real sandbox this session, cross-checked against an official Factus
 * documentation artifact.
 *
 * <p>Several fields exist only in Factus's catalog space, with no domain equivalent — ADR-001
 * forbids the reverse leak (Factus vocabulary into the domain), but the domain necessarily knows
 * nothing about DIAN's own code lists either, so this mapper supplies defaults for them,
 * documented at each one:
 *
 * <ul>
 *   <li>{@code payment_details} — the domain models no payment method (SRS 3 scope: no
 *       collections). Defaults to one cash entry ({@code payment_form=1, payment_method_code=10})
 *       covering the exact tax-inclusive total.
 *   <li>{@code unit_measure_code} — the domain uses UN/ECE Recommendation 20 codes ({@link
 *       InvoiceLine#quantity()}); Factus uses its own DIAN catalog. Only the codes RC-1/RC-2/RC-3
 *       actually use are mapped (see {@link #UNIT_CODE_MAP}); anything else fails loudly rather
 *       than guessing.
 *   <li>{@code standard_code} — DIAN's UNSPSC-family product classification, which the domain has
 *       no concept of at all. Defaults to {@code "999"} ("adopción del contribuyente" — Factus's
 *       own designation for "no specific standard classification").
 *   <li>{@code customer.identification_document_code} / {@code legal_organization_code} — derived
 *       from whether {@link Buyer#taxIdentifier()} is present, not stored by the domain. A
 *       present tax id is treated as a company (this project's own thesis is B2B cross-border
 *       sales); its absence falls back to the "consumidor final" shape the live sandbox itself
 *       echoed back for an anonymous identification during this session.
 *   <li>reverse-charge lines (RC-3) — DIAN has no EU-style reverse-charge mechanism; mapped to
 *       Factus's {@code is_excluded} exemption flag at 0%. The EN 16931 exemption reason text has
 *       no field to carry it in Factus's schema and is dropped — a scope simplification, not an
 *       oversight.
 * </ul>
 */
public final class FactusPayloadMapper {

  private static final Map<String, String> UNIT_CODE_MAP =
      Map.of(
          "C62", "94" // UN/ECE "one" -> DIAN "unidad"
          );

  private static final ObjectMapper JSON = new ObjectMapper();

  private final int numberingRangeId;

  public FactusPayloadMapper(int numberingRangeId) {
    this.numberingRangeId = numberingRangeId;
  }

  public com.fasterxml.jackson.databind.JsonNode toPayload(Invoice invoice) {
    ObjectNode root = JSON.createObjectNode();
    root.put("reference_code", invoice.businessKey());
    root.put("numbering_range_id", numberingRangeId);
    root.set("payment_details", paymentDetails(invoice));
    root.set("customer", customer(invoice.buyer()));
    root.set("items", items(invoice));
    return root;
  }

  private ArrayNode paymentDetails(Invoice invoice) {
    ArrayNode array = JSON.createArrayNode();
    ObjectNode entry = array.addObject();
    entry.put("payment_form", "1"); // contado (cash) — the domain models no other payment method
    entry.put("payment_method_code", "10"); // efectivo
    entry.put("amount", invoice.totals().taxInclusiveAmount().amount().toPlainString());
    return array;
  }

  private ObjectNode customer(Buyer buyer) {
    ObjectNode node = JSON.createObjectNode();
    if (buyer.taxIdentifier().isPresent()) {
      boolean isColombian = "CO".equals(buyer.countryCode());
      node.put("identification_document_code", isColombian ? "31" : "50"); // NIT / NIT de otro país
      node.put("identification", buyer.taxIdentifier().orElseThrow());
      node.put("legal_organization_code", "1"); // persona jurídica — this project's B2B thesis
      node.put("company", buyer.name());
    } else {
      // The exact shape the live sandbox echoed back for identification "222222222222".
      node.put("identification_document_code", "13"); // cédula ciudadanía
      node.put("identification", "222222222222");
      node.put("legal_organization_code", "2"); // persona natural
      node.put("names", "Consumidor final");
    }
    node.put("country_code", buyer.countryCode());
    node.put("tribute_code", "ZZ"); // no aplica
    ArrayNode responsibilities = node.putArray("responsibilities");
    responsibilities.add("R-99-PN"); // Factus's own default
    return node;
  }

  private ArrayNode items(Invoice invoice) {
    ArrayNode array = JSON.createArrayNode();
    for (InvoiceLine line : invoice.lines()) {
      array.add(item(line));
    }
    return array;
  }

  private ObjectNode item(InvoiceLine line) {
    ObjectNode node = JSON.createObjectNode();
    node.put("code_reference", line.lineIdentifier());
    node.put("name", line.itemName());
    // Factus requires "máximo dos decimales" as a string; the domain Quantity is scale 6
    // (T-101: physical amounts aren't money), so this reuses the project's own rounding mode
    // rather than truncating, at the one boundary where Factus's own format forces the loss.
    node.put("quantity", line.quantity().value().setScale(2, Money.ROUNDING).toPlainString());
    if (line.lineDiscount().isZero()) {
      node.put("discount_rate", "0.00");
    } else {
      node.put("discount_amount", line.lineDiscount().amount().toPlainString());
    }
    node.put("price", line.unitPrice().amount().toPlainString());
    node.put("unit_measure_code", mapUnitCode(line.quantity().unitCode()));
    node.put("standard_code", "999"); // "adopción del contribuyente" — no specific UNSPSC mapping
    node.set("taxes", taxes(line));
    return node;
  }

  private ArrayNode taxes(InvoiceLine line) {
    ArrayNode array = JSON.createArrayNode();
    ObjectNode tax = array.addObject();
    tax.put("code", "01"); // IVA
    if (line.taxCategory() == TaxCategory.REVERSE_CHARGE) {
      tax.put("rate", "0.00");
      tax.put("is_excluded", true);
    } else {
      tax.put("rate", line.taxRate().percentage().toPlainString());
    }
    return array;
  }

  private static String mapUnitCode(String domainUnitCode) {
    String mapped = UNIT_CODE_MAP.get(domainUnitCode);
    if (mapped == null) {
      throw new IllegalArgumentException(
          "no Factus unit_measure_code mapping for domain unit code \"" + domainUnitCode
              + "\" — add one to FactusPayloadMapper.UNIT_CODE_MAP rather than guess");
    }
    return mapped;
  }
}
