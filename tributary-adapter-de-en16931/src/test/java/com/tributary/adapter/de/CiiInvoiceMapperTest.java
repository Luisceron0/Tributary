package com.tributary.adapter.de;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.domain.Buyer;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * T-503: Invoice (EN 16931) -&gt; CII/XRechnung XML. Shape confirmed against a real official
 * KoSIT reference instance ({@code itplr-kosit/xrechnung-testsuite}, business case 01.01a,
 * uncefact/CII variant) fetched this session, not assumed from the standard's prose alone.
 * Field-by-field structural assertions here; full conformance (schema + Schematron, via the real
 * KoSIT validator) is T-505's job — this test only proves the mapper produces the shape the
 * validator will actually see.
 */
class CiiInvoiceMapperTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
  private static final CiiInvoiceMapper MAPPER = new CiiInvoiceMapper();

  private static final String RSM_NS = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
  private static final String RAM_NS =
      "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
  private static final String UDT_NS = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100";

  private Document parse(String xml) throws Exception {
    return SecureXmlFactory.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private Element firstChild(Element parent, String namespace, String localName) {
    NodeList children = parent.getElementsByTagNameNS(namespace, localName);
    assertTrue(children.getLength() > 0, "expected at least one " + localName + " under " + parent.getTagName());
    return (Element) children.item(0);
  }

  @Test
  @DisplayName("RC-1: the guideline ID declares XRechnung 3.0 conformance — this is what KoSIT's scenario matcher keys on")
  void rc1DeclaresTheXRechnungGuideline() throws Exception {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    Document doc = parse(MAPPER.toXml(invoice));

    assertEquals("CrossIndustryInvoice", doc.getDocumentElement().getLocalName());
    assertEquals(RSM_NS, doc.getDocumentElement().getNamespaceURI());
    Element guideline = firstChild(doc.getDocumentElement(), RAM_NS, "GuidelineSpecifiedDocumentContextParameter");
    Element id = firstChild(guideline, RAM_NS, "ID");
    assertEquals("urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0", id.getTextContent());
  }

  @Test
  @DisplayName("RC-1: document header fields map to ExchangedDocument")
  void rc1DocumentHeaderFields() throws Exception {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    Document doc = parse(MAPPER.toXml(invoice));

    Element exchangedDocument = firstChild(doc.getDocumentElement(), RSM_NS, "ExchangedDocument");
    assertEquals("biz-key-1", firstChild(exchangedDocument, RAM_NS, "ID").getTextContent());
    assertEquals("380", firstChild(exchangedDocument, RAM_NS, "TypeCode").getTextContent());
    Element issueDateTime = firstChild(exchangedDocument, RAM_NS, "IssueDateTime");
    Element dateTimeString = firstChild(issueDateTime, UDT_NS, "DateTimeString");
    assertEquals("20260815", dateTimeString.getTextContent());
    assertEquals("102", dateTimeString.getAttribute("format"));
  }

  @Test
  @DisplayName("RC-1: seller and buyer map to SellerTradeParty/BuyerTradeParty with VAT id and country")
  void rc1SellerAndBuyerParties() throws Exception {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    Document doc = parse(MAPPER.toXml(invoice));

    Element agreement = firstChild(doc.getDocumentElement(), RAM_NS, "ApplicableHeaderTradeAgreement");
    Element seller = firstChild(agreement, RAM_NS, "SellerTradeParty");
    assertEquals("Acme Exports SL", firstChild(seller, RAM_NS, "Name").getTextContent());
    Element sellerTaxReg = firstChild(seller, RAM_NS, "SpecifiedTaxRegistration");
    Element sellerVatId = firstChild(sellerTaxReg, RAM_NS, "ID");
    assertEquals("ESB12345678", sellerVatId.getTextContent());
    assertEquals("VA", sellerVatId.getAttribute("schemeID"));
    Element sellerAddress = firstChild(seller, RAM_NS, "PostalTradeAddress");
    assertEquals("ES", firstChild(sellerAddress, RAM_NS, "CountryID").getTextContent());

    Element buyer = firstChild(agreement, RAM_NS, "BuyerTradeParty");
    assertEquals("Handel GmbH", firstChild(buyer, RAM_NS, "Name").getTextContent());
    Element buyerTaxReg = firstChild(buyer, RAM_NS, "SpecifiedTaxRegistration");
    Element buyerVatId = firstChild(buyerTaxReg, RAM_NS, "ID");
    assertEquals("DE123456789", buyerVatId.getTextContent());
    Element buyerAddress = firstChild(buyer, RAM_NS, "PostalTradeAddress");
    assertEquals("DE", firstChild(buyerAddress, RAM_NS, "CountryID").getTextContent());
  }

  @Test
  @DisplayName("a buyer without a tax id omits BuyerTradeParty's SpecifiedTaxRegistration entirely, rather than sending an empty one")
  void buyerWithoutTaxIdOmitsTaxRegistration() throws Exception {
    Buyer anonymous = Buyer.withoutTaxIdentifier("Walk-in", "DE");
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, anonymous, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    Document doc = parse(MAPPER.toXml(invoice));

    Element agreement = firstChild(doc.getDocumentElement(), RAM_NS, "ApplicableHeaderTradeAgreement");
    Element buyer = firstChild(agreement, RAM_NS, "BuyerTradeParty");
    assertEquals(0, buyer.getElementsByTagNameNS(RAM_NS, "SpecifiedTaxRegistration").getLength());
  }

  @Test
  @DisplayName("RC-1: a standard-rate line maps quantity/price/tax fields exactly")
  void rc1LineFields() throws Exception {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "LINE-1", "Widgets", Quantity.of("3", "C62"), Money.of("10.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    Document doc = parse(MAPPER.toXml(invoice));

    Element transaction = firstChild(doc.getDocumentElement(), RSM_NS, "SupplyChainTradeTransaction");
    Element lineItem = firstChild(transaction, RAM_NS, "IncludedSupplyChainTradeLineItem");
    Element lineDoc = firstChild(lineItem, RAM_NS, "AssociatedDocumentLineDocument");
    assertEquals("LINE-1", firstChild(lineDoc, RAM_NS, "LineID").getTextContent());
    Element product = firstChild(lineItem, RAM_NS, "SpecifiedTradeProduct");
    assertEquals("Widgets", firstChild(product, RAM_NS, "Name").getTextContent());
    Element lineAgreement = firstChild(lineItem, RAM_NS, "SpecifiedLineTradeAgreement");
    Element netPrice = firstChild(lineAgreement, RAM_NS, "NetPriceProductTradePrice");
    assertEquals("10.00", firstChild(netPrice, RAM_NS, "ChargeAmount").getTextContent());
    Element delivery = firstChild(lineItem, RAM_NS, "SpecifiedLineTradeDelivery");
    Element quantity = firstChild(delivery, RAM_NS, "BilledQuantity");
    assertEquals("3.0000", quantity.getTextContent());
    assertEquals("C62", quantity.getAttribute("unitCode"));
    Element lineSettlement = firstChild(lineItem, RAM_NS, "SpecifiedLineTradeSettlement");
    Element lineTax = firstChild(lineSettlement, RAM_NS, "ApplicableTradeTax");
    assertEquals("VAT", firstChild(lineTax, RAM_NS, "TypeCode").getTextContent());
    assertEquals("S", firstChild(lineTax, RAM_NS, "CategoryCode").getTextContent());
    assertEquals("19.00", firstChild(lineTax, RAM_NS, "RateApplicablePercent").getTextContent());
    Element lineSummation = firstChild(lineSettlement, RAM_NS, "SpecifiedTradeSettlementLineMonetarySummation");
    assertEquals("30.00", firstChild(lineSummation, RAM_NS, "LineTotalAmount").getTextContent());
  }

  @Test
  @DisplayName("RC-3: a reverse-charge line maps to category AE, rate 0, with the exemption reason text at both line and document level")
  void rc3ReverseChargeLineCarriesExemptionReason() throws Exception {
    InvoiceLine line =
        InvoiceLine.reverseCharge(
            "1", "Consulting", Quantity.of("1", "C62"), Money.of("200.00", EUR), Money.zero(EUR),
            "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");
    Invoice invoice =
        Invoice.draft("biz-key-3", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    Document doc = parse(MAPPER.toXml(invoice));

    Element transaction = firstChild(doc.getDocumentElement(), RSM_NS, "SupplyChainTradeTransaction");
    Element lineItem = firstChild(transaction, RAM_NS, "IncludedSupplyChainTradeLineItem");
    Element lineSettlement = firstChild(lineItem, RAM_NS, "SpecifiedLineTradeSettlement");
    Element lineTax = firstChild(lineSettlement, RAM_NS, "ApplicableTradeTax");
    assertEquals("AE", firstChild(lineTax, RAM_NS, "CategoryCode").getTextContent());
    assertEquals("0.00", firstChild(lineTax, RAM_NS, "RateApplicablePercent").getTextContent());
    assertEquals(
        "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC",
        firstChild(lineTax, RAM_NS, "ExemptionReason").getTextContent());

    Element settlement = firstChild(doc.getDocumentElement(), RAM_NS, "ApplicableHeaderTradeSettlement");
    Element headerTax = firstChild(settlement, RAM_NS, "ApplicableTradeTax");
    assertEquals("AE", firstChild(headerTax, RAM_NS, "CategoryCode").getTextContent());
    assertEquals(
        "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC",
        firstChild(headerTax, RAM_NS, "ExemptionReason").getTextContent());
  }

  @Test
  @DisplayName("RC-2: multiple VAT groups each get their own ApplicableTradeTax at document level, matching InvoiceTotals' breakdown")
  void rc2MultipleVatGroupsAtHeaderLevel() throws Exception {
    InvoiceLine line19 =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    InvoiceLine line7 =
        InvoiceLine.standardRate(
            "2", "Books", Quantity.of("1", "C62"), Money.of("50.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("7"));
    Invoice invoice =
        Invoice.draft(
            "biz-key-2", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line19, line7), Money.zero(EUR));

    Document doc = parse(MAPPER.toXml(invoice));

    Element settlement = firstChild(doc.getDocumentElement(), RAM_NS, "ApplicableHeaderTradeSettlement");
    NodeList headerTaxes = settlement.getElementsByTagNameNS(RAM_NS, "ApplicableTradeTax");
    assertEquals(2, headerTaxes.getLength());

    Element summation = firstChild(settlement, RAM_NS, "SpecifiedTradeSettlementHeaderMonetarySummation");
    assertEquals("150.00", firstChild(summation, RAM_NS, "LineTotalAmount").getTextContent());
    assertEquals("150.00", firstChild(summation, RAM_NS, "TaxBasisTotalAmount").getTextContent());
    assertEquals("22.50", firstChild(summation, RAM_NS, "TaxTotalAmount").getTextContent());
    assertEquals("172.50", firstChild(summation, RAM_NS, "GrandTotalAmount").getTextContent());
    assertEquals("172.50", firstChild(summation, RAM_NS, "DuePayableAmount").getTextContent());
  }

  @Test
  @DisplayName("the invoice currency code is set once at header level")
  void invoiceCurrencyCode() throws Exception {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    Document doc = parse(MAPPER.toXml(invoice));

    Element settlement = firstChild(doc.getDocumentElement(), RAM_NS, "ApplicableHeaderTradeSettlement");
    assertEquals("EUR", firstChild(settlement, RAM_NS, "InvoiceCurrencyCode").getTextContent());
  }

  @Test
  @DisplayName("an unmapped unit code fails loudly rather than guessing, same discipline as the Factus mapper")
  void unmappedUnitCodeFailsLoudly() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "XYZ-UNKNOWN"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    // C62 ("one") is the only unit RC-1/2/3 need and the only one KoSIT's XRechnung scenario
    // guarantees a mapping for at this project's current scope — unlike Factus, unitCode here is
    // NOT translated (both the domain and CII use UN/ECE Rec. 20 codes), so "unmapped" means
    // "not on the project's own allowed list", not "no cross-catalog mapping exists".
    assertFalse(CiiInvoiceMapper.isSupportedUnitCode("XYZ-UNKNOWN"));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> MAPPER.toXml(invoice));
  }
}
