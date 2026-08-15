package com.tributary.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.tributary.adapter.es.VerifactuHasher;
import com.tributary.domain.Buyer;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import com.tributary.persistence.ChainVerifier;
import com.tributary.persistence.DataSourceFactory;
import com.tributary.persistence.FiscalRecordRepository;
import com.tributary.persistence.FlywayMigrator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-402: "after cancelling, the verifier still returns INTACT" — the one phase-4 criterion that
 * spans a whole adapter (ES's canonicalization/hashing) AND persistence (real chain storage and
 * verification) together. Neither module can host this alone (SRS 6.2: adapters depend on
 * application only, persistence depends on application only, neither on the other) —
 * {@code tributary-api} is the only module that sees both, the same reason ArchitectureTest lives
 * here (agreement A-2).
 */
@Testcontainers
class VerifactuChainIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withDatabaseName("tributary")
          .withUsername("tributary_owner")
          .withPassword("test-only-" + System.nanoTime());

  private static DataSource dataSource;

  @BeforeAll
  static void migrateSchema() {
    dataSource = DataSourceFactory.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    FlywayMigrator.migrate(dataSource);
  }

  @Test
  @DisplayName("T-402: an alta record followed by its anulación still verifies INTACT")
  void chainStaysIntactAfterCancellation() {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    FiscalRecordRepository repository = new FiscalRecordRepository(dataSource, new JdbcTransactionManager(dataSource));
    ChainVerifier verifier =
        new ChainVerifier(dataSource, (previousHash, canonicalPayload) -> VerifactuHasher.hash(canonicalPayload, previousHash));

    Currency eur = Currency.getInstance("EUR");
    Issuer issuer = new Issuer("Acme Exports SL", "ESB12345678", "ES");
    Buyer buyer = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", eur), Money.zero(eur),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", issuer, buyer, eur, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(eur));

    UUID issuerRowId = insertIssuer(jdbc, issuer);
    UUID buyerRowId = insertBuyer(jdbc, buyer);
    UUID invoiceId = insertDraftInvoice(jdbc, invoice, issuerRowId, buyerRowId);

    UUID chainId = UUID.randomUUID();
    Instant altaGeneratedAt = Instant.parse("2026-08-15T10:00:00Z");

    // The alta record: canonicalize from the domain Invoice, hash against the chain's current
    // (empty) tail, append.
    UUID altaRecordId =
        repository.append(
            invoiceId,
            "ES",
            "ISSUANCE",
            chainId,
            (head, sequence) -> {
              String canonical = VerifactuHasher.canonicalizeAlta(invoice, altaGeneratedAt);
              String hash = VerifactuHasher.hash(canonical, head.map(FiscalRecordRepository.ChainHead::hash));
              return new FiscalRecordRepository.NewRecord(hash, canonical);
            });
    String altaHash = jdbc.sql("SELECT hash FROM fiscal_record WHERE id = ?").param(altaRecordId).query(String.class).single();

    // RF-004: the cancellation references the alta record; it never edits it.
    Instant anulacionGeneratedAt = Instant.parse("2026-08-15T11:00:00Z");
    repository.append(
        invoiceId,
        "ES",
        "CANCELLATION",
        chainId,
        (head, sequence) -> {
          String canonical =
              VerifactuHasher.canonicalizeAnulacion(altaHash, "duplicate line item", anulacionGeneratedAt);
          String hash = VerifactuHasher.hash(canonical, head.map(FiscalRecordRepository.ChainHead::hash));
          return new FiscalRecordRepository.NewRecord(hash, canonical);
        });

    ChainVerifier.VerificationResult result = verifier.verify(chainId);

    assertInstanceOf(ChainVerifier.VerificationResult.Intact.class, result);
    assertEquals(2, ((ChainVerifier.VerificationResult.Intact) result).recordsVerified());
  }

  private static UUID insertIssuer(JdbcClient jdbc, Issuer issuer) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO issuer (id, name, tax_identifier, country_code) VALUES (?, ?, ?, ?)")
        .params(id, issuer.name(), issuer.taxIdentifier(), issuer.countryCode())
        .update();
    return id;
  }

  private static UUID insertBuyer(JdbcClient jdbc, Buyer buyer) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO buyer (id, name, tax_identifier, country_code) VALUES (?, ?, ?, ?)")
        .params(id, buyer.name(), buyer.taxIdentifier().orElse(null), buyer.countryCode())
        .update();
    return id;
  }

  private static UUID insertDraftInvoice(JdbcClient jdbc, Invoice invoice, UUID issuerId, UUID buyerId) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO invoice
              (id, business_key, state, issuer_id, buyer_id, currency, issue_date,
               sum_of_line_net_amounts, tax_exclusive_amount, tax_total, tax_inclusive_amount,
               amount_due_for_payment)
            VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            id,
            invoice.businessKey(),
            issuerId,
            buyerId,
            invoice.currency().getCurrencyCode(),
            invoice.issueDate(),
            invoice.totals().sumOfLineNetAmounts().amount(),
            invoice.totals().taxExclusiveAmount().amount(),
            invoice.totals().taxTotal().amount(),
            invoice.totals().taxInclusiveAmount().amount(),
            invoice.totals().amountDueForPayment().amount())
        .update();
    return id;
  }
}
