package com.tributary.adapter.de;

import com.tributary.domain.Buyer;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.VatBreakdown;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * T-503: Invoice (EN 16931) -&gt; CII/XRechnung XML, via JAXB (SRS 6.2's stated technology for
 * this module). Element names, namespaces and ordering were confirmed against real, independently
 * KoSIT-validated reference instances from {@code itplr-kosit/xrechnung-testsuite} fetched this
 * session — the header-level {@code ApplicableTradeTax} sequence in particular (CalculatedAmount,
 * TypeCode, ExemptionReason, BasisAmount, CategoryCode, RateApplicablePercent) comes from a real
 * exempt-category sample, not a guess. No reference sample in that suite exercises a line-level
 * {@code ExemptionReason}; that placement is this mapper's best-effort ordering, to be confirmed
 * or corrected against the real KoSIT validator in T-505 — the same empirical-iteration approach
 * that worked for the Factus payload shape in T-303/T-307.
 *
 * <p>Fields the domain does not model — payment means/terms, buyer reference — are omitted
 * entirely rather than sent empty; every reference sample confirms these are optional (minOccurs
 * 0) in the real schema, matching {@code FactusPayloadMapper}'s "the domain models no payment
 * method" scope decision (T-303) applied here too.
 */
public final class CiiInvoiceMapper {

  static final String RSM_NS = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
  static final String RAM_NS = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
  static final String UDT_NS = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100";

  private static final String XRECHNUNG_GUIDELINE_ID =
      "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0";
  private static final String INVOICE_TYPE_CODE = "380"; // commercial invoice
  private static final String VAT_TYPE_CODE = "VAT";
  private static final String VAT_SCHEME_ID = "VA";
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

  /**
   * The domain and CII both use UN/ECE Recommendation 20 unit codes, so — unlike {@code
   * FactusPayloadMapper}, which translates into a different DIAN catalog — this is a scope
   * allowlist, not a cross-catalog mapping: only the codes RC-1/2/3 actually use.
   */
  private static final Set<String> SUPPORTED_UNIT_CODES = Set.of("C62");

  private final JAXBContext jaxbContext;

  public CiiInvoiceMapper() {
    try {
      this.jaxbContext = JAXBContext.newInstance(CrossIndustryInvoice.class);
    } catch (JAXBException e) {
      throw new IllegalStateException("could not initialise the CII JAXB context", e);
    }
  }

  static boolean isSupportedUnitCode(String domainUnitCode) {
    return SUPPORTED_UNIT_CODES.contains(domainUnitCode);
  }

  public String toXml(Invoice invoice) {
    for (InvoiceLine line : invoice.lines()) {
      if (!isSupportedUnitCode(line.quantity().unitCode())) {
        throw new IllegalArgumentException(
            "no CII mapping for domain unit code \"" + line.quantity().unitCode()
                + "\" — add it to CiiInvoiceMapper.SUPPORTED_UNIT_CODES rather than guess");
      }
    }

    CrossIndustryInvoice root = new CrossIndustryInvoice();
    root.exchangedDocumentContext = exchangedDocumentContext();
    root.exchangedDocument = exchangedDocument(invoice);
    root.supplyChainTradeTransaction = supplyChainTradeTransaction(invoice);

    try {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
      StringWriter writer = new StringWriter();
      marshaller.marshal(root, writer);
      return writer.toString();
    } catch (JAXBException e) {
      throw new IllegalStateException("could not marshal CII XML", e);
    }
  }

  private ExchangedDocumentContext exchangedDocumentContext() {
    ExchangedDocumentContext context = new ExchangedDocumentContext();
    context.guidelineSpecifiedDocumentContextParameter = new GuidelineSpecifiedDocumentContextParameter();
    context.guidelineSpecifiedDocumentContextParameter.id = XRECHNUNG_GUIDELINE_ID;
    return context;
  }

  private ExchangedDocument exchangedDocument(Invoice invoice) {
    ExchangedDocument document = new ExchangedDocument();
    document.id = invoice.businessKey();
    document.typeCode = INVOICE_TYPE_CODE;
    document.issueDateTime = new IssueDateTime();
    document.issueDateTime.dateTimeString = new DateTimeString();
    document.issueDateTime.dateTimeString.format = "102";
    document.issueDateTime.dateTimeString.value = invoice.issueDate().format(DATE_FORMAT);
    return document;
  }

  private SupplyChainTradeTransaction supplyChainTradeTransaction(Invoice invoice) {
    SupplyChainTradeTransaction transaction = new SupplyChainTradeTransaction();
    transaction.lineItems = new ArrayList<>();
    for (InvoiceLine line : invoice.lines()) {
      transaction.lineItems.add(lineItem(line));
    }
    transaction.applicableHeaderTradeAgreement = headerTradeAgreement(invoice);
    transaction.applicableHeaderTradeDelivery = new ApplicableHeaderTradeDelivery();
    transaction.applicableHeaderTradeSettlement = headerTradeSettlement(invoice);
    return transaction;
  }

  private IncludedSupplyChainTradeLineItem lineItem(InvoiceLine line) {
    IncludedSupplyChainTradeLineItem item = new IncludedSupplyChainTradeLineItem();

    item.associatedDocumentLineDocument = new AssociatedDocumentLineDocument();
    item.associatedDocumentLineDocument.lineId = line.lineIdentifier();

    item.specifiedTradeProduct = new SpecifiedTradeProduct();
    item.specifiedTradeProduct.name = line.itemName();

    item.specifiedLineTradeAgreement = new SpecifiedLineTradeAgreement();
    item.specifiedLineTradeAgreement.netPriceProductTradePrice = new NetPriceProductTradePrice();
    item.specifiedLineTradeAgreement.netPriceProductTradePrice.chargeAmount =
        line.unitPrice().amount().toPlainString();

    item.specifiedLineTradeDelivery = new SpecifiedLineTradeDelivery();
    item.specifiedLineTradeDelivery.billedQuantity = new BilledQuantity();
    item.specifiedLineTradeDelivery.billedQuantity.unitCode = line.quantity().unitCode();
    item.specifiedLineTradeDelivery.billedQuantity.value =
        line.quantity().value().setScale(4, Money.ROUNDING).toPlainString();

    item.specifiedLineTradeSettlement = new SpecifiedLineTradeSettlement();
    LineApplicableTradeTax lineTax = new LineApplicableTradeTax();
    lineTax.typeCode = VAT_TYPE_CODE;
    lineTax.exemptionReason = line.vatExemptionReason().orElse(null);
    lineTax.categoryCode = line.taxCategory().code();
    lineTax.rateApplicablePercent = line.taxRate().percentage().toPlainString();
    item.specifiedLineTradeSettlement.applicableTradeTax = lineTax;
    item.specifiedLineTradeSettlement.specifiedTradeSettlementLineMonetarySummation =
        new SpecifiedTradeSettlementLineMonetarySummation();
    item.specifiedLineTradeSettlement.specifiedTradeSettlementLineMonetarySummation.lineTotalAmount =
        line.netAmount().amount().toPlainString();

    return item;
  }

  private ApplicableHeaderTradeAgreement headerTradeAgreement(Invoice invoice) {
    ApplicableHeaderTradeAgreement agreement = new ApplicableHeaderTradeAgreement();
    agreement.sellerTradeParty = sellerParty(invoice.issuer());
    agreement.buyerTradeParty = buyerParty(invoice.buyer());
    return agreement;
  }

  private TradeParty sellerParty(Issuer issuer) {
    TradeParty party = new TradeParty();
    party.name = issuer.name();
    party.postalTradeAddress = new PostalTradeAddress();
    party.postalTradeAddress.countryId = issuer.countryCode();
    party.specifiedTaxRegistration = taxRegistration(issuer.taxIdentifier());
    return party;
  }

  private TradeParty buyerParty(Buyer buyer) {
    TradeParty party = new TradeParty();
    party.name = buyer.name();
    party.postalTradeAddress = new PostalTradeAddress();
    party.postalTradeAddress.countryId = buyer.countryCode();
    party.specifiedTaxRegistration = buyer.taxIdentifier().map(this::taxRegistration).orElse(null);
    return party;
  }

  private SpecifiedTaxRegistration taxRegistration(String vatId) {
    SpecifiedTaxRegistration registration = new SpecifiedTaxRegistration();
    registration.id = new SchemedId();
    registration.id.schemeId = VAT_SCHEME_ID;
    registration.id.value = vatId;
    return registration;
  }

  private ApplicableHeaderTradeSettlement headerTradeSettlement(Invoice invoice) {
    ApplicableHeaderTradeSettlement settlement = new ApplicableHeaderTradeSettlement();
    settlement.invoiceCurrencyCode = invoice.currency().getCurrencyCode();

    settlement.applicableTradeTax = new ArrayList<>();
    for (VatBreakdown breakdown : invoice.totals().vatBreakdown()) {
      HeaderApplicableTradeTax tax = new HeaderApplicableTradeTax();
      tax.calculatedAmount = breakdown.taxAmount().amount().toPlainString();
      tax.typeCode = VAT_TYPE_CODE;
      tax.exemptionReason = breakdown.exemptionReason().orElse(null);
      tax.basisAmount = breakdown.taxableAmount().amount().toPlainString();
      tax.categoryCode = breakdown.category().code();
      tax.rateApplicablePercent = breakdown.rate().percentage().toPlainString();
      settlement.applicableTradeTax.add(tax);
    }

    settlement.specifiedTradeSettlementHeaderMonetarySummation = new SpecifiedTradeSettlementHeaderMonetarySummation();
    var summation = settlement.specifiedTradeSettlementHeaderMonetarySummation;
    summation.lineTotalAmount = invoice.totals().sumOfLineNetAmounts().amount().toPlainString();
    summation.taxBasisTotalAmount = invoice.totals().taxExclusiveAmount().amount().toPlainString();
    summation.taxTotalAmount = new CurrencyAmount();
    summation.taxTotalAmount.currencyId = invoice.currency().getCurrencyCode();
    summation.taxTotalAmount.value = invoice.totals().taxTotal().amount().toPlainString();
    summation.grandTotalAmount = invoice.totals().taxInclusiveAmount().amount().toPlainString();
    summation.duePayableAmount = invoice.totals().amountDueForPayment().amount().toPlainString();

    return settlement;
  }

  // ---- JAXB model: element names/namespaces/order confirmed against real reference instances ----

  @XmlRootElement(name = "CrossIndustryInvoice", namespace = RSM_NS)
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      namespace = RSM_NS,
      propOrder = {"exchangedDocumentContext", "exchangedDocument", "supplyChainTradeTransaction"})
  static final class CrossIndustryInvoice {
    @XmlElement(name = "ExchangedDocumentContext", namespace = RSM_NS)
    ExchangedDocumentContext exchangedDocumentContext;

    @XmlElement(name = "ExchangedDocument", namespace = RSM_NS)
    ExchangedDocument exchangedDocument;

    @XmlElement(name = "SupplyChainTradeTransaction", namespace = RSM_NS)
    SupplyChainTradeTransaction supplyChainTradeTransaction;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class ExchangedDocumentContext {
    @XmlElement(name = "GuidelineSpecifiedDocumentContextParameter", namespace = RAM_NS)
    GuidelineSpecifiedDocumentContextParameter guidelineSpecifiedDocumentContextParameter;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class GuidelineSpecifiedDocumentContextParameter {
    @XmlElement(name = "ID", namespace = RAM_NS)
    String id;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"id", "typeCode", "issueDateTime"})
  static final class ExchangedDocument {
    @XmlElement(name = "ID", namespace = RAM_NS)
    String id;

    @XmlElement(name = "TypeCode", namespace = RAM_NS)
    String typeCode;

    @XmlElement(name = "IssueDateTime", namespace = RAM_NS)
    IssueDateTime issueDateTime;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class IssueDateTime {
    @XmlElement(name = "DateTimeString", namespace = UDT_NS)
    DateTimeString dateTimeString;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class DateTimeString {
    @XmlAttribute(name = "format")
    String format;

    @jakarta.xml.bind.annotation.XmlValue
    String value;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      propOrder = {
        "lineItems",
        "applicableHeaderTradeAgreement",
        "applicableHeaderTradeDelivery",
        "applicableHeaderTradeSettlement"
      })
  static final class SupplyChainTradeTransaction {
    @XmlElement(name = "IncludedSupplyChainTradeLineItem", namespace = RAM_NS)
    List<IncludedSupplyChainTradeLineItem> lineItems;

    @XmlElement(name = "ApplicableHeaderTradeAgreement", namespace = RAM_NS)
    ApplicableHeaderTradeAgreement applicableHeaderTradeAgreement;

    @XmlElement(name = "ApplicableHeaderTradeDelivery", namespace = RAM_NS)
    ApplicableHeaderTradeDelivery applicableHeaderTradeDelivery;

    @XmlElement(name = "ApplicableHeaderTradeSettlement", namespace = RAM_NS)
    ApplicableHeaderTradeSettlement applicableHeaderTradeSettlement;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      propOrder = {
        "associatedDocumentLineDocument",
        "specifiedTradeProduct",
        "specifiedLineTradeAgreement",
        "specifiedLineTradeDelivery",
        "specifiedLineTradeSettlement"
      })
  static final class IncludedSupplyChainTradeLineItem {
    @XmlElement(name = "AssociatedDocumentLineDocument", namespace = RAM_NS)
    AssociatedDocumentLineDocument associatedDocumentLineDocument;

    @XmlElement(name = "SpecifiedTradeProduct", namespace = RAM_NS)
    SpecifiedTradeProduct specifiedTradeProduct;

    @XmlElement(name = "SpecifiedLineTradeAgreement", namespace = RAM_NS)
    SpecifiedLineTradeAgreement specifiedLineTradeAgreement;

    @XmlElement(name = "SpecifiedLineTradeDelivery", namespace = RAM_NS)
    SpecifiedLineTradeDelivery specifiedLineTradeDelivery;

    @XmlElement(name = "SpecifiedLineTradeSettlement", namespace = RAM_NS)
    SpecifiedLineTradeSettlement specifiedLineTradeSettlement;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class AssociatedDocumentLineDocument {
    @XmlElement(name = "LineID", namespace = RAM_NS)
    String lineId;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class SpecifiedTradeProduct {
    @XmlElement(name = "Name", namespace = RAM_NS)
    String name;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class SpecifiedLineTradeAgreement {
    @XmlElement(name = "NetPriceProductTradePrice", namespace = RAM_NS)
    NetPriceProductTradePrice netPriceProductTradePrice;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class NetPriceProductTradePrice {
    @XmlElement(name = "ChargeAmount", namespace = RAM_NS)
    String chargeAmount;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class SpecifiedLineTradeDelivery {
    @XmlElement(name = "BilledQuantity", namespace = RAM_NS)
    BilledQuantity billedQuantity;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class BilledQuantity {
    @XmlAttribute(name = "unitCode")
    String unitCode;

    @jakarta.xml.bind.annotation.XmlValue
    String value;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"applicableTradeTax", "specifiedTradeSettlementLineMonetarySummation"})
  static final class SpecifiedLineTradeSettlement {
    @XmlElement(name = "ApplicableTradeTax", namespace = RAM_NS)
    LineApplicableTradeTax applicableTradeTax;

    @XmlElement(name = "SpecifiedTradeSettlementLineMonetarySummation", namespace = RAM_NS)
    SpecifiedTradeSettlementLineMonetarySummation specifiedTradeSettlementLineMonetarySummation;
  }

  /** Line-level order confirmed present in real samples: TypeCode, CategoryCode[, RateApplicablePercent]. ExemptionReason placement is this mapper's best effort — see class note. */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"typeCode", "exemptionReason", "categoryCode", "rateApplicablePercent"})
  static final class LineApplicableTradeTax {
    @XmlElement(name = "TypeCode", namespace = RAM_NS)
    String typeCode;

    @XmlElement(name = "ExemptionReason", namespace = RAM_NS)
    String exemptionReason;

    @XmlElement(name = "CategoryCode", namespace = RAM_NS)
    String categoryCode;

    @XmlElement(name = "RateApplicablePercent", namespace = RAM_NS)
    String rateApplicablePercent;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class SpecifiedTradeSettlementLineMonetarySummation {
    @XmlElement(name = "LineTotalAmount", namespace = RAM_NS)
    String lineTotalAmount;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"sellerTradeParty", "buyerTradeParty"})
  static final class ApplicableHeaderTradeAgreement {
    @XmlElement(name = "SellerTradeParty", namespace = RAM_NS)
    TradeParty sellerTradeParty;

    @XmlElement(name = "BuyerTradeParty", namespace = RAM_NS)
    TradeParty buyerTradeParty;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"name", "postalTradeAddress", "specifiedTaxRegistration"})
  static final class TradeParty {
    @XmlElement(name = "Name", namespace = RAM_NS)
    String name;

    @XmlElement(name = "PostalTradeAddress", namespace = RAM_NS)
    PostalTradeAddress postalTradeAddress;

    @XmlElement(name = "SpecifiedTaxRegistration", namespace = RAM_NS)
    SpecifiedTaxRegistration specifiedTaxRegistration;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class PostalTradeAddress {
    @XmlElement(name = "CountryID", namespace = RAM_NS)
    String countryId;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class SpecifiedTaxRegistration {
    @XmlElement(name = "ID", namespace = RAM_NS)
    SchemedId id;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class SchemedId {
    @XmlAttribute(name = "schemeID")
    String schemeId;

    @jakarta.xml.bind.annotation.XmlValue
    String value;
  }

  /** Deliberately empty — the domain models no delivery details; the real schema still requires the element. */
  @XmlAccessorType(XmlAccessType.FIELD)
  static final class ApplicableHeaderTradeDelivery {}

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      propOrder = {"invoiceCurrencyCode", "applicableTradeTax", "specifiedTradeSettlementHeaderMonetarySummation"})
  static final class ApplicableHeaderTradeSettlement {
    @XmlElement(name = "InvoiceCurrencyCode", namespace = RAM_NS)
    String invoiceCurrencyCode;

    @XmlElement(name = "ApplicableTradeTax", namespace = RAM_NS)
    List<HeaderApplicableTradeTax> applicableTradeTax;

    @XmlElement(name = "SpecifiedTradeSettlementHeaderMonetarySummation", namespace = RAM_NS)
    SpecifiedTradeSettlementHeaderMonetarySummation specifiedTradeSettlementHeaderMonetarySummation;
  }

  /** Order confirmed against a real exempt-category (O) reference instance — see class note. */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"calculatedAmount", "typeCode", "exemptionReason", "basisAmount", "categoryCode", "rateApplicablePercent"})
  static final class HeaderApplicableTradeTax {
    @XmlElement(name = "CalculatedAmount", namespace = RAM_NS)
    String calculatedAmount;

    @XmlElement(name = "TypeCode", namespace = RAM_NS)
    String typeCode;

    @XmlElement(name = "ExemptionReason", namespace = RAM_NS)
    String exemptionReason;

    @XmlElement(name = "BasisAmount", namespace = RAM_NS)
    String basisAmount;

    @XmlElement(name = "CategoryCode", namespace = RAM_NS)
    String categoryCode;

    @XmlElement(name = "RateApplicablePercent", namespace = RAM_NS)
    String rateApplicablePercent;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      propOrder = {"lineTotalAmount", "taxBasisTotalAmount", "taxTotalAmount", "grandTotalAmount", "duePayableAmount"})
  static final class SpecifiedTradeSettlementHeaderMonetarySummation {
    @XmlElement(name = "LineTotalAmount", namespace = RAM_NS)
    String lineTotalAmount;

    @XmlElement(name = "TaxBasisTotalAmount", namespace = RAM_NS)
    String taxBasisTotalAmount;

    @XmlElement(name = "TaxTotalAmount", namespace = RAM_NS)
    CurrencyAmount taxTotalAmount;

    @XmlElement(name = "GrandTotalAmount", namespace = RAM_NS)
    String grandTotalAmount;

    @XmlElement(name = "DuePayableAmount", namespace = RAM_NS)
    String duePayableAmount;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class CurrencyAmount {
    @XmlAttribute(name = "currencyID")
    String currencyId;

    @jakarta.xml.bind.annotation.XmlValue
    String value;
  }
}
