package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-304's own persistence dependency: a real, PostgreSQL-backed {@code InvoiceRepository}. */
class JdbcInvoiceRepositoryTest extends AbstractPostgresTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  private JdbcInvoiceRepository repository() {
    return new JdbcInvoiceRepository(dataSource, new JdbcKeyVaultRepository(dataSource));
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
  @DisplayName("T-601/RF-007: buyer PII round-trips exactly, and the stored bytes never contain the plaintext — checked with a raw SQL read, not through this repository's own decrypt path")
  void buyerPiiRoundTripsAndIsGenuinelyEncryptedAtRest() {
    Buyer buyerWithPii =
        Buyer.withTaxIdentifier("Handel GmbH", "DE999888777", "DE")
            .withPersonalData(
                Optional.of("Hauptstraße 1, 10115 Berlin"), Optional.of("buyer@handel.invalid"),
                Optional.of("+49 30 1234567"));
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft(
            "biz-" + java.util.UUID.randomUUID(), ISSUER, buyerWithPii, EUR, LocalDate.of(2026, 8, 15),
            List.of(line), Money.zero(EUR));

    JdbcInvoiceRepository repo = repository();
    repo.save(invoice);
    Invoice found = repo.findByBusinessKey(invoice.businessKey()).orElseThrow();

    assertEquals(buyerWithPii.name(), found.buyer().name());
    assertEquals(buyerWithPii.address(), found.buyer().address());
    assertEquals(buyerWithPii.email(), found.buyer().email());
    assertEquals(buyerWithPii.phone(), found.buyer().phone());

    // RF-007's own literal acceptance criterion, checked at the SQL level, not through PiiCipher —
    // "el texto claro no aparece en ningún volcado de la base de datos" (pg_dump would show these
    // exact bytes).
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    byte[][] blobs =
        jdbc.sql("SELECT name_encrypted, address_encrypted, email_encrypted, phone_encrypted FROM buyer WHERE tax_identifier = ?")
            .param(buyerWithPii.taxIdentifier().orElseThrow())
            .query(
                (rs, rowNum) ->
                    new byte[][] {
                      rs.getBytes("name_encrypted"), rs.getBytes("address_encrypted"),
                      rs.getBytes("email_encrypted"), rs.getBytes("phone_encrypted")
                    })
            .single();
    String[] plaintexts = {"Handel GmbH", "Hauptstraße 1, 10115 Berlin", "buyer@handel.invalid", "+49 30 1234567"};
    for (int i = 0; i < blobs.length; i++) {
      String rawBytesAsLatin1 = new String(blobs[i], java.nio.charset.StandardCharsets.ISO_8859_1);
      assertTrue(
          !rawBytesAsLatin1.contains(plaintexts[i]),
          "column " + i + " leaks its plaintext as a literal substring in the raw stored bytes");
    }
  }

  @Test
  @DisplayName("ADR-004: two buyers with the IDENTICAL name get DIFFERENT ciphertext bytes for it — a random IV per operation, not derived from content")
  void identicalNamesProduceDifferentCiphertextAcrossBuyers() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Buyer sameNameBuyerA = Buyer.withTaxIdentifier("Identical Name GmbH", "DE111111111", "DE");
    Buyer sameNameBuyerB = Buyer.withTaxIdentifier("Identical Name GmbH", "DE222222222", "DE");

    JdbcInvoiceRepository repo = repository();
    repo.save(
        Invoice.draft(
            "biz-" + java.util.UUID.randomUUID(), ISSUER, sameNameBuyerA, EUR, LocalDate.of(2026, 8, 15),
            List.of(line), Money.zero(EUR)));
    repo.save(
        Invoice.draft(
            "biz-" + java.util.UUID.randomUUID(), ISSUER, sameNameBuyerB, EUR, LocalDate.of(2026, 8, 15),
            List.of(line), Money.zero(EUR)));

    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    byte[] nameA =
        jdbc.sql("SELECT name_encrypted FROM buyer WHERE tax_identifier = ?").param("DE111111111").query(byte[].class).single();
    byte[] nameB =
        jdbc.sql("SELECT name_encrypted FROM buyer WHERE tax_identifier = ?").param("DE222222222").query(byte[].class).single();

    assertTrue(
        !java.util.Arrays.equals(nameA, nameB),
        "identical plaintext under different keys/IVs must never produce identical ciphertext");
  }

  @Test
  @DisplayName("T-602: a buyer with a DRAFT invoice has an active retention obligation")
  void draftInvoiceIsAnActiveRetentionObligation() {
    Buyer buyer = Buyer.withTaxIdentifier("Retention Test GmbH", "DE333333333", "DE");
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(
            "biz-" + java.util.UUID.randomUUID(), ISSUER, buyer, EUR, LocalDate.of(2026, 8, 15),
            List.of(line), Money.zero(EUR));

    JdbcInvoiceRepository repo = repository();
    repo.save(draft);
    UUID buyerId = buyerIdFor(draft.businessKey());

    assertTrue(repo.hasActiveRetentionObligation(buyerId), "a DRAFT invoice is still mid-transaction");
  }

  @Test
  @DisplayName("T-602: a buyer whose only invoice reached a terminal state (ISSUED) has no active retention obligation")
  void terminalInvoiceIsNotAnActiveRetentionObligation() {
    Buyer buyer = Buyer.withTaxIdentifier("Retention Test 2 GmbH", "DE444444444", "DE");
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(
            "biz-" + java.util.UUID.randomUUID(), ISSUER, buyer, EUR, LocalDate.of(2026, 8, 15),
            List.of(line), Money.zero(EUR));

    JdbcInvoiceRepository repo = repository();
    repo.save(draft);
    UUID buyerId = buyerIdFor(draft.businessKey());
    UUID invoiceId =
        org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
            .sql("SELECT id FROM invoice WHERE business_key = ?")
            .param(draft.businessKey())
            .query((rs, rowNum) -> (UUID) rs.getObject("id"))
            .single();
    // T-203's own trigger requires a real issuance_attempt before ISSUED is a legal transition.
    TestFixtures.insertAcceptedIssuanceAttempt(dataSource, invoiceId, "ext-ref-" + invoiceId);
    repo.tryTransition(draft.businessKey(), DocumentState.DRAFT, DocumentState.SUBMITTING);
    repo.tryTransition(draft.businessKey(), DocumentState.SUBMITTING, DocumentState.ISSUED);

    assertFalse(repo.hasActiveRetentionObligation(buyerId), "ISSUED is a terminal state — nothing is still in flight");
  }

  private UUID buyerIdFor(String businessKey) {
    return org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
        .sql("SELECT buyer_id FROM invoice WHERE business_key = ?")
        .param(businessKey)
        .query((rs, rowNum) -> (UUID) rs.getObject("buyer_id"))
        .single();
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
