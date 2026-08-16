package com.tributary.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.application.port.CancellationResult;
import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.IssuanceOutcome;
import com.tributary.application.port.IssuanceResult;
import com.tributary.application.port.RegimeQueryResult;
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
import com.tributary.persistence.JdbcKeyVaultRepository;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-308: 20 threads racing {@link IssueInvoiceUseCase#execute} on the SAME businessKey must
 * produce exactly one call to the regime and exactly one document reaching ISSUED — real
 * PostgreSQL, real concurrent JDBC connections, not a single-threaded simulation.
 */
@Testcontainers
class IssueInvoiceConcurrencyTest {

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
  @DisplayName("T-308's literal criterion: 20 threads on the same document produce exactly one issuance")
  void twentyThreadsOnTheSameDocumentProduceExactlyOneIssuance() throws InterruptedException {
    JdbcInvoiceRepository invoiceRepository = new JdbcInvoiceRepository(dataSource, new JdbcKeyVaultRepository(dataSource));
    JdbcIssuanceAttemptRepository attemptRepository = new JdbcIssuanceAttemptRepository(dataSource);

    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    String businessKey = "biz-" + java.util.UUID.randomUUID();
    Invoice draft =
        Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
    invoiceRepository.save(draft);

    AtomicInteger issueCallCount = new AtomicInteger();
    FiscalRegimePort countingRegime =
        new FiscalRegimePort() {
          @Override
          public IssuanceResult issue(Invoice invoice) {
            issueCallCount.incrementAndGet();
            return new IssuanceResult(IssuanceOutcome.ACCEPTED, Optional.of("CUFE-CONCURRENCY-TEST"), List.of(), "{}");
          }

          @Override
          public CancellationResult cancel(Invoice original, String reason) {
            throw new UnsupportedOperationException();
          }

          @Override
          public RegimeQueryResult query(String businessKey) {
            throw new UnsupportedOperationException();
          }
        };

    IssueInvoiceUseCase useCase = new IssueInvoiceUseCase(invoiceRepository, attemptRepository, countingRegime, "CO");

    int threadCount = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);
    List<IssueInvoiceResult> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    for (int i = 0; i < threadCount; i++) {
      pool.submit(
          () -> {
            ready.countDown();
            try {
              start.await(10, TimeUnit.SECONDS);
              results.add(useCase.execute(businessKey));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }

    ready.await(10, TimeUnit.SECONDS);
    start.countDown();
    boolean completed = done.await(30, TimeUnit.SECONDS);
    pool.shutdown();

    assertTrue(completed, "all threads must finish within the timeout");
    assertEquals(1, issueCallCount.get(), "exactly one call to the regime for 20 concurrent callers on the same document");

    long issuedCount = results.stream().filter(r -> r instanceof IssueInvoiceResult.Issued).count();
    assertEquals(1, issuedCount, "exactly one caller sees Issued");

    long invalidStateCount = results.stream().filter(r -> r instanceof IssueInvoiceResult.InvalidState).count();
    assertEquals(threadCount - 1, invalidStateCount, "the other 19 must see InvalidState, having lost the race");

    Invoice finalInvoice = invoiceRepository.findByBusinessKey(businessKey).orElseThrow();
    assertEquals(DocumentState.ISSUED, finalInvoice.state());
    assertEquals(1, invoiceRepository.countByBusinessKey(businessKey), "still exactly one document, never duplicated");
  }
}
