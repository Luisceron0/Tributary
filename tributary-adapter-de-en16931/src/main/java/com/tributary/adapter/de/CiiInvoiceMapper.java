package com.tributary.adapter.de;

import com.tributary.domain.Buyer;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.TaxCategory;
import com.tributary.domain.TaxRate;
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
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * T-503: Invoice (EN 16931) -&gt; CII/XRechnung XML, via JAXB (SRS 6.2's stated technology for
 * this module). Element names, namespaces and ordering were confirmed against real, independently
 * KoSIT-validated reference instances from {@code itplr-kosit/xrechnung-testsuite} fetched this
 * session, then iterated against the real KoSIT validator itself (T-505) until all three reference
 * cases were genuinely accepted — not assumed from the standard's prose, and not declared done
 * until the real tool agreed.
 *
 * <p>Several elements the real XRechnung 3.0.2 Schematron ruleset requires have no domain
 * equivalent at all (SRS 3 never asked for a street address, a contact person, a buyer-routing
 * reference, or payment terms) — this mapper defaults them, each documented at the point it is
 * set, using the SAME placeholder convention KoSIT's own official reference instances use for
 * fields their own test data can't supply (bracketed text like {@code "[Seller city]"}, or the
 * literal German {@code "nicht vorhanden"} — "not available" — for a contact person KoSIT's own
 * samples use verbatim). This is a scope simplification, declared the same way {@code
 * FactusPayloadMapper} declared its own (T-303), not an oversight: extending {@code Issuer}/{@code
 * Buyer} to model a street address is out of scope for what RC-1/2/3 need (see {@code
 * tasks/todo.md}'s explicit warning against widening the domain beyond the three reference cases).
 */
public final class CiiInvoiceMapper {

  static final String RSM_NS = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
  static final String RAM_NS = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
  static final String UDT_NS = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100";

  private static final String BUSINESS_PROCESS_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";
  private static final String XRECHNUNG_GUIDELINE_ID =
      "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0";
  private static final String INVOICE_TYPE_CODE = "380"; // commercial invoice
  private static final String VAT_TYPE_CODE = "VAT";
  private static final String VAT_SCHEME_ID = "VA";
  private static final String ELECTRONIC_ADDRESS_SCHEME_ID = "EM"; // electronic mail
  private static final String PAYMENT_MEANS_TYPE_CODE_UNSPECIFIED = "1"; // UNCL4461: instrument not defined
  private static final String PAYMENT_TERMS_DESCRIPTION = "Zahlbar sofort ohne Abzug."; // matches the official sample
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
    // Required by the real XRechnung Schematron ruleset (PEPPOL-EN16931-R001) — confirmed live
    // against the KoSIT validator (T-505); the domain has no concept of a "business process", so
    // this uses the same standard PEPPOL billing process URN every real sample fetched this
    // session declares.
    context.businessProcessSpecifiedDocumentContextParameter = new DocumentContextParameter();
    context.businessProcessSpecifiedDocumentContextParameter.id = BUSINESS_PROCESS_ID;
    context.guidelineSpecifiedDocumentContextParameter = new DocumentContextParameter();
    context.guidelineSpecifiedDocumentContextParameter.id = XRECHNUNG_GUIDELINE_ID;
    return context;
  }

  private ExchangedDocument exchangedDocument(Invoice invoice) {
    ExchangedDocument document = new ExchangedDocument();
    document.id = invoice.businessKey();
    document.typeCode = INVOICE_TYPE_CODE;
    document.issueDateTime = new IssueDateTime();
    document.issueDateTime.dateTimeString = dateTimeString(invoice.issueDate().format(DATE_FORMAT));
    return document;
  }

  private DateTimeString dateTimeString(String yyyyMMdd) {
    DateTimeString dateTimeString = new DateTimeString();
    dateTimeString.format = "102";
    dateTimeString.value = yyyyMMdd;
    return dateTimeString;
  }

  private SupplyChainTradeTransaction supplyChainTradeTransaction(Invoice invoice) {
    SupplyChainTradeTransaction transaction = new SupplyChainTradeTransaction();
    transaction.lineItems = new ArrayList<>();
    for (InvoiceLine line : invoice.lines()) {
      transaction.lineItems.add(lineItem(line));
    }
    transaction.applicableHeaderTradeAgreement = headerTradeAgreement(invoice);

    // BR-DE-TMP-32 / PEPPOL-EN16931-R008 (confirmed live, T-505): the delivery element must carry
    // an actual delivery date, not be empty — the domain has no delivery-date concept distinct
    // from the invoice's own issue date, so this reuses it rather than fabricating a new one.
    transaction.applicableHeaderTradeDelivery = new ApplicableHeaderTradeDelivery();
    transaction.applicableHeaderTradeDelivery.actualDeliverySupplyChainEvent = new ActualDeliverySupplyChainEvent();
    transaction.applicableHeaderTradeDelivery.actualDeliverySupplyChainEvent.occurrenceDateTime =
        new OccurrenceDateTime();
    transaction.applicableHeaderTradeDelivery.actualDeliverySupplyChainEvent.occurrenceDateTime.dateTimeString =
        dateTimeString(invoice.issueDate().format(DATE_FORMAT));

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
    if (line.lineDiscount().isZero()) {
      item.specifiedLineTradeAgreement.netPriceProductTradePrice = new NetPriceProductTradePrice();
      item.specifiedLineTradeAgreement.netPriceProductTradePrice.chargeAmount =
          line.unitPrice().amount().toPlainString();
    } else {
      // PEPPOL-EN16931-R120 (confirmed live, T-505): LineTotalAmount must reconcile against
      // quantity * unit price adjusted by an EXPLICIT per-unit allowance — the domain's
      // lineDiscount is a total, not per-unit, amount (see netAmount()'s own formula), so it is
      // divided by quantity here purely for this per-unit CII field; the line's own total
      // (already correctly discounted) still comes from netAmount(), never recomputed from this.
      BigDecimal perUnitDiscount =
          line.lineDiscount().amount().divide(line.quantity().value(), 4, Money.ROUNDING);
      item.specifiedLineTradeAgreement.grossPriceProductTradePrice = new GrossPriceProductTradePrice();
      item.specifiedLineTradeAgreement.grossPriceProductTradePrice.chargeAmount =
          line.unitPrice().amount().toPlainString();
      item.specifiedLineTradeAgreement.grossPriceProductTradePrice.appliedTradeAllowanceCharge =
          new AppliedTradeAllowanceCharge();
      item.specifiedLineTradeAgreement.grossPriceProductTradePrice.appliedTradeAllowanceCharge.chargeIndicator =
          new ChargeIndicator();
      item.specifiedLineTradeAgreement.grossPriceProductTradePrice.appliedTradeAllowanceCharge.chargeIndicator
              .indicator =
          "false";
      item.specifiedLineTradeAgreement.grossPriceProductTradePrice.appliedTradeAllowanceCharge.actualAmount =
          perUnitDiscount.toPlainString();

      item.specifiedLineTradeAgreement.netPriceProductTradePrice = new NetPriceProductTradePrice();
      item.specifiedLineTradeAgreement.netPriceProductTradePrice.chargeAmount =
          line.unitPrice().amount().subtract(perUnitDiscount).toPlainString();
    }

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
    // BR-DE-15 (confirmed live, T-505): mandatory buyer-routing reference. The domain's closest
    // existing concept is its own businessKey (ADR-003) — not an EN 16931 term, but the only
    // per-invoice identifier available without inventing a new domain field for this alone.
    agreement.buyerReference = invoice.businessKey();
    agreement.sellerTradeParty = sellerParty(invoice.issuer());
    agreement.buyerTradeParty = buyerParty(invoice.buyer());
    return agreement;
  }

  private TradeParty sellerParty(Issuer issuer) {
    TradeParty party = new TradeParty();
    party.name = issuer.name();
    // BR-DE-2 (confirmed live, T-505): mandatory seller contact (BG-6). The domain models no
    // contact person — "nicht vorhanden" ("not available") is KoSIT's own official reference
    // instances' literal convention for this exact gap, adopted verbatim rather than invented.
    party.definedTradeContact = placeholderContact();
    party.postalTradeAddress = placeholderAddress(issuer.countryCode(), "Seller");
    party.uriUniversalCommunication = placeholderElectronicAddress("seller");
    party.specifiedTaxRegistration = taxRegistration(issuer.taxIdentifier());
    return party;
  }

  private TradeParty buyerParty(Buyer buyer) {
    TradeParty party = new TradeParty();
    party.name = buyer.name();
    party.postalTradeAddress = placeholderAddress(buyer.countryCode(), "Buyer");
    party.uriUniversalCommunication = placeholderElectronicAddress("buyer");
    party.specifiedTaxRegistration = buyer.taxIdentifier().map(this::taxRegistration).orElse(null);
    return party;
  }

  private DefinedTradeContact placeholderContact() {
    // BR-DE-27/BR-DE-28/BR-DE-7 (confirmed live, T-505): a placeholder phone needs real digits
    // (BR-DE-27 requires at least three) and a placeholder email needs to actually look like one
    // (exactly one '@', at least two characters either side) — a purely bracketed "[not modelled]"
    // string satisfies BR-DE-2's "the group is present" but fails these format-level rules.
    DefinedTradeContact contact = new DefinedTradeContact();
    contact.personName = "nicht vorhanden";
    contact.telephoneUniversalCommunication = new TelephoneUniversalCommunication();
    contact.telephoneUniversalCommunication.completeNumber = "+00 000 0000000";
    contact.emailUriUniversalCommunication = new EmailUriUniversalCommunication();
    contact.emailUriUniversalCommunication.uriId = "seller-contact@example.invalid";
    return contact;
  }

  private PostalTradeAddress placeholderAddress(String countryCode, String label) {
    PostalTradeAddress address = new PostalTradeAddress();
    // BR-DE-3/4/8/9 (confirmed live, T-505): street/city/postcode have no domain equivalent (SRS
    // 3 — Issuer/Buyer model only name, VAT id and country). Bracketed placeholders, exactly the
    // convention the official KoSIT reference instances use for the same gap.
    address.postcodeCode = "[" + label + " postal code]";
    address.lineOne = "[" + label + " address line 1]";
    address.cityName = "[" + label + " city]";
    address.countryId = countryCode;
    return address;
  }

  private UriUniversalCommunication placeholderElectronicAddress(String label) {
    // BT-34/BT-49, PEPPOL-EN16931-R010/R020 (confirmed live, T-505): mandatory e-invoicing
    // routing address, no domain equivalent. example.invalid is RFC 2606's reserved-for-
    // documentation domain, deliberately not a real address.
    UriUniversalCommunication uri = new UriUniversalCommunication();
    uri.uriId = new SchemedId();
    uri.uriId.schemeId = ELECTRONIC_ADDRESS_SCHEME_ID;
    uri.uriId.value = label + "@example.invalid";
    return uri;
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

    // BR-DE-1 (confirmed live, T-505): mandatory payment means (BG-16). The domain models no
    // payment method (same scope decision as FactusPayloadMapper's payment_details, T-303) — "1"
    // is UNCL4461's own code for "instrument not defined", an honest explicit non-answer rather
    // than a fabricated bank account.
    settlement.specifiedTradeSettlementPaymentMeans = new SpecifiedTradeSettlementPaymentMeans();
    settlement.specifiedTradeSettlementPaymentMeans.typeCode = PAYMENT_MEANS_TYPE_CODE_UNSPECIFIED;

    settlement.applicableTradeTax = new ArrayList<>();
    settlement.specifiedTradeAllowanceCharge = new ArrayList<>();
    for (VatBreakdown breakdown : invoice.totals().vatBreakdown()) {
      HeaderApplicableTradeTax tax = new HeaderApplicableTradeTax();
      tax.calculatedAmount = breakdown.taxAmount().amount().toPlainString();
      tax.typeCode = VAT_TYPE_CODE;
      tax.exemptionReason = breakdown.exemptionReason().orElse(null);
      tax.basisAmount = breakdown.taxableAmount().amount().toPlainString();
      tax.categoryCode = breakdown.category().code();
      tax.rateApplicablePercent = breakdown.rate().percentage().toPlainString();
      settlement.applicableTradeTax.add(tax);

      // BR-S-08/BR-CO-13 (confirmed live, T-505 — RC-2 specifically): a document-level allowance
      // (BT-107) apportioned across VAT groups (T-101's own algorithm) must be reported per group
      // as its own BG-20 allowance, or the Schematron cross-check between BT-116 (this group's
      // taxable amount, already net of its share) and the raw line total for that rate fails —
      // it has no other way to see where the difference went. Recomputes this group's raw net
      // total the same way InvoiceTotals.compute() groups lines, then derives the apportioned
      // share as (raw net) - (already-computed taxable amount) rather than reimplementing T-101's
      // proportional-with-residual algorithm a second time.
      Money groupRawNet =
          Money.sum(
              invoice.currency(),
              invoice.lines().stream()
                  .filter(l -> l.taxCategory() == breakdown.category() && l.taxRate().equals(breakdown.rate()))
                  .map(InvoiceLine::netAmount)
                  .toList());
      Money groupAllowance = groupRawNet.minus(breakdown.taxableAmount());
      if (!groupAllowance.isZero()) {
        settlement.specifiedTradeAllowanceCharge.add(allowanceCharge(groupAllowance, breakdown.category(), breakdown.rate()));
      }
    }

    // BR-DE-15's sibling for terms (BR-CO-25, confirmed live, T-505): if the amount due is
    // positive, either a due date or payment terms must be present. The domain tracks neither a
    // due date nor terms text — reuses the exact wording the official KoSIT sample uses for the
    // same "paid immediately, no terms" default.
    if (!invoice.totals().amountDueForPayment().isZero()) {
      settlement.specifiedTradePaymentTerms = new SpecifiedTradePaymentTerms();
      settlement.specifiedTradePaymentTerms.description = PAYMENT_TERMS_DESCRIPTION;
    }

    settlement.specifiedTradeSettlementHeaderMonetarySummation = new SpecifiedTradeSettlementHeaderMonetarySummation();
    var summation = settlement.specifiedTradeSettlementHeaderMonetarySummation;
    summation.lineTotalAmount = invoice.totals().sumOfLineNetAmounts().amount().toPlainString();
    if (!invoice.totals().documentLevelAllowance().isZero()) {
      summation.allowanceTotalAmount = invoice.totals().documentLevelAllowance().amount().toPlainString();
    }
    summation.taxBasisTotalAmount = invoice.totals().taxExclusiveAmount().amount().toPlainString();
    summation.taxTotalAmount = new CurrencyAmount();
    summation.taxTotalAmount.currencyId = invoice.currency().getCurrencyCode();
    summation.taxTotalAmount.value = invoice.totals().taxTotal().amount().toPlainString();
    summation.grandTotalAmount = invoice.totals().taxInclusiveAmount().amount().toPlainString();
    summation.duePayableAmount = invoice.totals().amountDueForPayment().amount().toPlainString();

    return settlement;
  }

  private SpecifiedTradeAllowanceCharge allowanceCharge(Money amount, TaxCategory category, TaxRate rate) {
    SpecifiedTradeAllowanceCharge charge = new SpecifiedTradeAllowanceCharge();
    charge.chargeIndicator = new ChargeIndicator();
    charge.chargeIndicator.indicator = "false"; // an allowance, never a charge — BT-107 not BT-108
    charge.actualAmount = amount.amount().toPlainString();
    charge.reason = "Rabatt"; // "discount" — the domain carries no allowance reason text (BT-97 out of scope)
    charge.categoryTradeTax = new AllowanceCategoryTradeTax();
    charge.categoryTradeTax.typeCode = VAT_TYPE_CODE;
    charge.categoryTradeTax.categoryCode = category.code();
    charge.categoryTradeTax.rateApplicablePercent = rate.percentage().toPlainString();
    return charge;
  }

  // ---- JAXB model: element names/namespaces/order confirmed against real reference instances,
  // then against the real KoSIT validator itself (T-505) ----

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
  @XmlType(propOrder = {"businessProcessSpecifiedDocumentContextParameter", "guidelineSpecifiedDocumentContextParameter"})
  static final class ExchangedDocumentContext {
    @XmlElement(name = "BusinessProcessSpecifiedDocumentContextParameter", namespace = RAM_NS)
    DocumentContextParameter businessProcessSpecifiedDocumentContextParameter;

    @XmlElement(name = "GuidelineSpecifiedDocumentContextParameter", namespace = RAM_NS)
    DocumentContextParameter guidelineSpecifiedDocumentContextParameter;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class DocumentContextParameter {
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
  @XmlType(propOrder = {"grossPriceProductTradePrice", "netPriceProductTradePrice"})
  static final class SpecifiedLineTradeAgreement {
    @XmlElement(name = "GrossPriceProductTradePrice", namespace = RAM_NS)
    GrossPriceProductTradePrice grossPriceProductTradePrice;

    @XmlElement(name = "NetPriceProductTradePrice", namespace = RAM_NS)
    NetPriceProductTradePrice netPriceProductTradePrice;
  }

  /** Order confirmed against a real per-unit line-discount reference instance (01.21a) — see class note. */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"chargeAmount", "appliedTradeAllowanceCharge"})
  static final class GrossPriceProductTradePrice {
    @XmlElement(name = "ChargeAmount", namespace = RAM_NS)
    String chargeAmount;

    @XmlElement(name = "AppliedTradeAllowanceCharge", namespace = RAM_NS)
    AppliedTradeAllowanceCharge appliedTradeAllowanceCharge;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"chargeIndicator", "actualAmount"})
  static final class AppliedTradeAllowanceCharge {
    @XmlElement(name = "ChargeIndicator", namespace = RAM_NS)
    ChargeIndicator chargeIndicator;

    @XmlElement(name = "ActualAmount", namespace = RAM_NS)
    String actualAmount;
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

  /** Order confirmed twice: TypeCode/CategoryCode/RateApplicablePercent against real reference samples, and the ExemptionReason position specifically against the real KoSIT validator via RC-3 (T-505) — no reference sample in the testsuite happened to exercise it at line level. */
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
  @XmlType(propOrder = {"buyerReference", "sellerTradeParty", "buyerTradeParty"})
  static final class ApplicableHeaderTradeAgreement {
    @XmlElement(name = "BuyerReference", namespace = RAM_NS)
    String buyerReference;

    @XmlElement(name = "SellerTradeParty", namespace = RAM_NS)
    TradeParty sellerTradeParty;

    @XmlElement(name = "BuyerTradeParty", namespace = RAM_NS)
    TradeParty buyerTradeParty;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"name", "definedTradeContact", "postalTradeAddress", "uriUniversalCommunication", "specifiedTaxRegistration"})
  static final class TradeParty {
    @XmlElement(name = "Name", namespace = RAM_NS)
    String name;

    @XmlElement(name = "DefinedTradeContact", namespace = RAM_NS)
    DefinedTradeContact definedTradeContact;

    @XmlElement(name = "PostalTradeAddress", namespace = RAM_NS)
    PostalTradeAddress postalTradeAddress;

    @XmlElement(name = "URIUniversalCommunication", namespace = RAM_NS)
    UriUniversalCommunication uriUniversalCommunication;

    @XmlElement(name = "SpecifiedTaxRegistration", namespace = RAM_NS)
    SpecifiedTaxRegistration specifiedTaxRegistration;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"personName", "telephoneUniversalCommunication", "emailUriUniversalCommunication"})
  static final class DefinedTradeContact {
    @XmlElement(name = "PersonName", namespace = RAM_NS)
    String personName;

    @XmlElement(name = "TelephoneUniversalCommunication", namespace = RAM_NS)
    TelephoneUniversalCommunication telephoneUniversalCommunication;

    @XmlElement(name = "EmailURIUniversalCommunication", namespace = RAM_NS)
    EmailUriUniversalCommunication emailUriUniversalCommunication;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class TelephoneUniversalCommunication {
    @XmlElement(name = "CompleteNumber", namespace = RAM_NS)
    String completeNumber;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class EmailUriUniversalCommunication {
    @XmlElement(name = "URIID", namespace = RAM_NS)
    String uriId;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class UriUniversalCommunication {
    @XmlElement(name = "URIID", namespace = RAM_NS)
    SchemedId uriId;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"postcodeCode", "lineOne", "cityName", "countryId"})
  static final class PostalTradeAddress {
    @XmlElement(name = "PostcodeCode", namespace = RAM_NS)
    String postcodeCode;

    @XmlElement(name = "LineOne", namespace = RAM_NS)
    String lineOne;

    @XmlElement(name = "CityName", namespace = RAM_NS)
    String cityName;

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

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class ApplicableHeaderTradeDelivery {
    @XmlElement(name = "ActualDeliverySupplyChainEvent", namespace = RAM_NS)
    ActualDeliverySupplyChainEvent actualDeliverySupplyChainEvent;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class ActualDeliverySupplyChainEvent {
    @XmlElement(name = "OccurrenceDateTime", namespace = RAM_NS)
    OccurrenceDateTime occurrenceDateTime;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class OccurrenceDateTime {
    @XmlElement(name = "DateTimeString", namespace = UDT_NS)
    DateTimeString dateTimeString;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      propOrder = {
        "invoiceCurrencyCode",
        "specifiedTradeSettlementPaymentMeans",
        "applicableTradeTax",
        "specifiedTradeAllowanceCharge",
        "specifiedTradePaymentTerms",
        "specifiedTradeSettlementHeaderMonetarySummation"
      })
  static final class ApplicableHeaderTradeSettlement {
    @XmlElement(name = "InvoiceCurrencyCode", namespace = RAM_NS)
    String invoiceCurrencyCode;

    @XmlElement(name = "SpecifiedTradeSettlementPaymentMeans", namespace = RAM_NS)
    SpecifiedTradeSettlementPaymentMeans specifiedTradeSettlementPaymentMeans;

    @XmlElement(name = "ApplicableTradeTax", namespace = RAM_NS)
    List<HeaderApplicableTradeTax> applicableTradeTax;

    @XmlElement(name = "SpecifiedTradeAllowanceCharge", namespace = RAM_NS)
    List<SpecifiedTradeAllowanceCharge> specifiedTradeAllowanceCharge;

    @XmlElement(name = "SpecifiedTradePaymentTerms", namespace = RAM_NS)
    SpecifiedTradePaymentTerms specifiedTradePaymentTerms;

    @XmlElement(name = "SpecifiedTradeSettlementHeaderMonetarySummation", namespace = RAM_NS)
    SpecifiedTradeSettlementHeaderMonetarySummation specifiedTradeSettlementHeaderMonetarySummation;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class SpecifiedTradeSettlementPaymentMeans {
    @XmlElement(name = "TypeCode", namespace = RAM_NS)
    String typeCode;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class SpecifiedTradePaymentTerms {
    @XmlElement(name = "Description", namespace = RAM_NS)
    String description;
  }

  /** Order confirmed against a real document-level-charge reference instance (01.21a) — see class note. */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"chargeIndicator", "actualAmount", "reason", "categoryTradeTax"})
  static final class SpecifiedTradeAllowanceCharge {
    @XmlElement(name = "ChargeIndicator", namespace = RAM_NS)
    ChargeIndicator chargeIndicator;

    @XmlElement(name = "ActualAmount", namespace = RAM_NS)
    String actualAmount;

    @XmlElement(name = "Reason", namespace = RAM_NS)
    String reason;

    @XmlElement(name = "CategoryTradeTax", namespace = RAM_NS)
    AllowanceCategoryTradeTax categoryTradeTax;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  static final class ChargeIndicator {
    @XmlElement(name = "Indicator", namespace = UDT_NS)
    String indicator;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(propOrder = {"typeCode", "categoryCode", "rateApplicablePercent"})
  static final class AllowanceCategoryTradeTax {
    @XmlElement(name = "TypeCode", namespace = RAM_NS)
    String typeCode;

    @XmlElement(name = "CategoryCode", namespace = RAM_NS)
    String categoryCode;

    @XmlElement(name = "RateApplicablePercent", namespace = RAM_NS)
    String rateApplicablePercent;
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

  /** Order per UN/CEFACT CII's TradeSettlementHeaderMonetarySummationType (ChargeTotalAmount omitted — the domain models no document-level charges). */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      propOrder = {
        "lineTotalAmount",
        "allowanceTotalAmount",
        "taxBasisTotalAmount",
        "taxTotalAmount",
        "grandTotalAmount",
        "duePayableAmount"
      })
  static final class SpecifiedTradeSettlementHeaderMonetarySummation {
    @XmlElement(name = "LineTotalAmount", namespace = RAM_NS)
    String lineTotalAmount;

    @XmlElement(name = "AllowanceTotalAmount", namespace = RAM_NS)
    String allowanceTotalAmount;

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
