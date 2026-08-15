package com.tributary.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.IssuanceOutcome;
import com.tributary.application.port.IssuanceResult;
import com.tributary.application.usecase.IssueInvoiceResult;
import com.tributary.application.usecase.IssueInvoiceUseCase;
import com.tributary.domain.Buyer;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import com.tributary.persistence.DataSourceFactory;
import com.tributary.persistence.FlywayMigrator;
import com.tributary.persistence.JdbcInvoiceRepository;
import com.tributary.persistence.JdbcIssuanceAttemptRepository;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-304: RF-002's own acceptance criterion, proven literally — the DRAFT -&gt; SUBMITTING
 * transition must be visible from a SEPARATE database connection before the network call to the
 * regime happens. The fake {@link FiscalRegimePort} used here opens its OWN {@link DataSource}
 * connection (not the use case's) inside {@code issue()} and reads the invoice's state right
 * there — the strongest way to prove the ordering without instrumenting the use case itself.
 */
@Testcontainers
class IssueInvoiceIntegrationTest {

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

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  @Test
  @DisplayName("T-304: SUBMITTING is visible from another connection before the network call — proven with a real second connection")
  void submittingIsVisibleFromAnotherConnectionBeforeNetworkIo() {
    JdbcInvoiceRepository invoiceRepository = new JdbcInvoiceRepository(dataSource);
    JdbcIssuanceAttemptRepository attemptRepository = new JdbcIssuanceAttemptRepository(dataSource);

    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    String businessKey = "biz-" + java.util.UUID.randomUUID();
    Invoice draft =
        Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
    invoiceRepository.save(draft);

    AtomicReference<DocumentState> stateSeenDuringNetworkCall = new AtomicReference<>();
    FiscalRegimePort fakeRegime =
        new FiscalRegimePort() {
          @Override
          public IssuanceResult issue(Invoice invoice) {
            // A SEPARATE connection/repository instance — not the one the use case used to save.
            JdbcClient separateConnection = JdbcClient.create(dataSource);
            String state =
                separateConnection
                    .sql("SELECT state FROM invoice WHERE business_key = ?")
                    .param(businessKey)
                    .query(String.class)
                    .single();
            stateSeenDuringNetworkCall.set(DocumentState.valueOf(state));
            return new IssuanceResult(IssuanceOutcome.ACCEPTED, Optional.of("CUFE-TEST-123"), List.of(), "{}");
          }

          @Override
          public com.tributary.application.port.CancellationResult cancel(Invoice original, String reason) {
            throw new UnsupportedOperationException();
          }

          @Override
          public com.tributary.application.port.RegimeQueryResult query(String businessKey) {
            throw new UnsupportedOperationException();
          }
        };

    IssueInvoiceUseCase useCase = new IssueInvoiceUseCase(invoiceRepository, attemptRepository, fakeRegime, "CO");
    IssueInvoiceResult result = useCase.execute(businessKey);

    assertEquals(DocumentState.SUBMITTING, stateSeenDuringNetworkCall.get(), "must be SUBMITTING when the network call happens, seen via a separate connection");
    assertInstanceOf(IssueInvoiceResult.Issued.class, result);
    assertEquals(DocumentState.ISSUED, ((IssueInvoiceResult.Issued) result).invoice().state());
  }

  @Test
  @DisplayName("a document not in DRAFT is refused without any network call")
  void refusesANonDraftDocument() {
    JdbcInvoiceRepository invoiceRepository = new JdbcInvoiceRepository(dataSource);
    JdbcIssuanceAttemptRepository attemptRepository = new JdbcIssuanceAttemptRepository(dataSource);

    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    String businessKey = "biz-" + java.util.UUID.randomUUID();
    Invoice draft =
        Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
    invoiceRepository.save(draft);
    invoiceRepository.save(draft.transitionTo(DocumentState.SUBMITTING));

    java.util.concurrent.atomic.AtomicBoolean networkCallMade = new java.util.concurrent.atomic.AtomicBoolean(false);
    FiscalRegimePort fakeRegime =
        new FiscalRegimePort() {
          @Override
          public IssuanceResult issue(Invoice invoice) {
            networkCallMade.set(true);
            throw new AssertionError("must not be called for a non-DRAFT document");
          }

          @Override
          public com.tributary.application.port.CancellationResult cancel(Invoice original, String reason) {
            throw new UnsupportedOperationException();
          }

          @Override
          public com.tributary.application.port.RegimeQueryResult query(String businessKey) {
            throw new UnsupportedOperationException();
          }
        };

    IssueInvoiceUseCase useCase = new IssueInvoiceUseCase(invoiceRepository, attemptRepository, fakeRegime, "CO");
    IssueInvoiceResult result = useCase.execute(businessKey);

    assertInstanceOf(IssueInvoiceResult.InvalidState.class, result);
    assertTrue(!networkCallMade.get());
  }
}
