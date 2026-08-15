package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.domain.Buyer;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-304's own persistence dependency: a real, PostgreSQL-backed {@code InvoiceRepository}. */
class JdbcInvoiceRepositoryTest extends AbstractPostgresTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  private JdbcInvoiceRepository repository() {
    return new JdbcInvoiceRepository(dataSource);
  }

  private Invoice sampleInvoice(String businessKey) {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("2", "C62"), Money.of("10.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    return Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
  }

  @Test
  @DisplayName("a saved draft round-trips through find with every field intact")
  void savedDraftRoundTrips() {
    Invoice invoice = sampleInvoice("biz-" + java.util.UUID.randomUUID());
    JdbcInvoiceRepository repo = repository();

    repo.save(invoice);
    Invoice found = repo.findByBusinessKey(invoice.businessKey()).orElseThrow();

    assertEquals(invoice.businessKey(), found.businessKey());
    assertEquals(DocumentState.DRAFT, found.state());
    assertEquals(invoice.issuer(), found.issuer());
    assertEquals(invoice.buyer(), found.buyer());
    assertEquals(invoice.currency(), found.currency());
    assertEquals(invoice.issueDate(), found.issueDate());
    assertEquals(1, found.lines().size());
    assertEquals(invoice.lines().get(0).lineIdentifier(), found.lines().get(0).lineIdentifier());
    assertEquals(invoice.totals().taxInclusiveAmount(), found.totals().taxInclusiveAmount());
  }

  @Test
  @DisplayName("a second save on the same businessKey updates state only, never re-inserts lines")
  void secondSaveUpdatesStateOnly() {
    Invoice draft = sampleInvoice("biz-" + java.util.UUID.randomUUID());
    JdbcInvoiceRepository repo = repository();
    repo.save(draft);

    Invoice submitting = draft.transitionTo(DocumentState.SUBMITTING);
    repo.save(submitting);

    Invoice found = repo.findByBusinessKey(draft.businessKey()).orElseThrow();
    assertEquals(DocumentState.SUBMITTING, found.state());
    assertEquals(1, found.lines().size(), "lines must not be duplicated by a second save");
    assertEquals(1, repo.countByBusinessKey(draft.businessKey()));
  }

  @Test
  @DisplayName("a buyer without a tax identifier round-trips as empty, not a sentinel string")
  void buyerWithoutTaxIdRoundTrips() {
    Buyer anonymous = Buyer.withoutTaxIdentifier("Walk-in", "CO");
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft(
            "biz-" + java.util.UUID.randomUUID(), ISSUER, anonymous, EUR, LocalDate.of(2026, 8, 15),
            List.of(line), Money.zero(EUR));

    JdbcInvoiceRepository repo = repository();
    repo.save(invoice);
    Invoice found = repo.findByBusinessKey(invoice.businessKey()).orElseThrow();

    assertTrue(found.buyer().taxIdentifier().isEmpty());
  }

  @Test
  @DisplayName("an unknown businessKey returns empty")
  void unknownBusinessKeyReturnsEmpty() {
    assertTrue(repository().findByBusinessKey("never-existed").isEmpty());
  }

  @Test
  @DisplayName("the same issuer tax_identifier is reused across invoices, not duplicated")
  void issuerIsReusedAcrossInvoices() {
    JdbcInvoiceRepository repo = repository();
    repo.save(sampleInvoice("biz-" + java.util.UUID.randomUUID()));
    repo.save(sampleInvoice("biz-" + java.util.UUID.randomUUID()));

    Long issuerCount =
        org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM issuer WHERE tax_identifier = ?")
            .param(ISSUER.taxIdentifier())
            .query(Long.class)
            .single();
    assertEquals(1L, issuerCount);
  }
}
