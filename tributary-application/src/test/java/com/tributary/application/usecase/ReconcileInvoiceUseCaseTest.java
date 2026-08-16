package com.tributary.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.application.port.CancellationResult;
import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.InvoiceRepository;
import com.tributary.application.port.IssuanceAttemptPort;
import com.tributary.application.port.IssuanceOutcome;
import com.tributary.application.port.IssuanceResult;
import com.tributary.application.port.QueryOutcome;
import com.tributary.application.port.RegimeQueryResult;
import com.tributary.domain.Buyer;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-306 (RF-008): {@link ReconcileInvoiceUseCase} must query the regime BEFORE ever deciding to
 * retry issuance — "ninguna emisión sin consulta previa." A hand-rolled call-recording fake
 * (rather than a mocking library — not a dependency this project needs for one ordering
 * assertion) proves the actual order, not just that both methods were eventually called.
 */
class ReconcileInvoiceUseCaseTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  /** Records every call in order — the whole point of this test. */
  private static final class RecordingFiscalRegimePort implements FiscalRegimePort {
    final List<String> callOrder = new ArrayList<>();
    RegimeQueryResult queryResponse;
    IssuanceResult issueResponse;

    @Override
    public IssuanceResult issue(Invoice invoice) {
      callOrder.add("issue");
      return issueResponse;
    }

    @Override
    public CancellationResult cancel(Invoice original, String reason) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RegimeQueryResult query(String businessKey) {
      callOrder.add("query");
      return queryResponse;
    }
  }

  private static final class InMemoryInvoiceRepository implements InvoiceRepository {
    private final Map<String, Invoice> byKey = new HashMap<>();
    private final Map<String, java.util.UUID> idsByKey = new HashMap<>();

    @Override
    public Optional<Invoice> findByBusinessKey(String businessKey) {
      return Optional.ofNullable(byKey.get(businessKey));
    }

    @Override
    public Optional<java.util.UUID> findIdByBusinessKey(String businessKey) {
      return Optional.ofNullable(idsByKey.get(businessKey));
    }

    @Override
    public void save(Invoice invoice) {
      byKey.put(invoice.businessKey(), invoice);
      idsByKey.computeIfAbsent(invoice.businessKey(), key -> java.util.UUID.randomUUID());
    }

    @Override
    public long countByBusinessKey(String businessKey) {
      return byKey.containsKey(businessKey) ? 1 : 0;
    }

    @Override
    public boolean tryTransition(String businessKey, DocumentState from, DocumentState to) {
      Invoice current = byKey.get(businessKey);
      if (current == null || current.state() != from) {
        return false;
      }
      byKey.put(businessKey, current.transitionTo(to));
      return true;
    }
  }

  private static final class NoOpIssuanceAttemptPort implements IssuanceAttemptPort {
    @Override
    public void record(String businessKey, String regime, IssuanceResult result) {}
  }

  private Invoice needsReconciliationInvoice(String businessKey) {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
    return draft.transitionTo(DocumentState.SUBMITTING).transitionTo(DocumentState.NEEDS_RECONCILIATION);
  }

  @Test
  @DisplayName("T-306's literal criterion: query happens before any issue() call, for every outcome")
  void queryAlwaysPrecedesIssue() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RecordingFiscalRegimePort regime = new RecordingFiscalRegimePort();
    String businessKey = "biz-key-1";
    repository.save(needsReconciliationInvoice(businessKey));

    regime.queryResponse = new RegimeQueryResult(QueryOutcome.NOT_FOUND, Optional.empty(), List.of());
    regime.issueResponse = new IssuanceResult(IssuanceOutcome.ACCEPTED, Optional.of("cufe-1"), List.of(), "{}");

    ReconcileInvoiceUseCase useCase =
        new ReconcileInvoiceUseCase(repository, new NoOpIssuanceAttemptPort(), regime, "CO");
    useCase.execute(businessKey, 0);

    assertEquals(List.of("query", "issue"), regime.callOrder);
  }

  @Test
  @DisplayName("FOUND_VALIDATED adopts the external reference without ever calling issue()")
  void foundValidatedAdoptsWithoutReissuing() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RecordingFiscalRegimePort regime = new RecordingFiscalRegimePort();
    String businessKey = "biz-key-1";
    repository.save(needsReconciliationInvoice(businessKey));
    regime.queryResponse = new RegimeQueryResult(QueryOutcome.FOUND_VALIDATED, Optional.of("cufe-adopted"), List.of());

    ReconcileInvoiceResult result =
        new ReconcileInvoiceUseCase(repository, new NoOpIssuanceAttemptPort(), regime, "CO").execute(businessKey, 0);

    assertEquals(List.of("query"), regime.callOrder, "adopting a found+validated document must never call issue()");
    assertInstanceOf(ReconcileInvoiceResult.Adopted.class, result);
    assertEquals(DocumentState.ISSUED, ((ReconcileInvoiceResult.Adopted) result).invoice().state());
  }

  @Test
  @DisplayName("FOUND_REJECTED confirms rejection without reissuing")
  void foundRejectedConfirmsWithoutReissuing() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RecordingFiscalRegimePort regime = new RecordingFiscalRegimePort();
    String businessKey = "biz-key-1";
    repository.save(needsReconciliationInvoice(businessKey));
    regime.queryResponse = new RegimeQueryResult(QueryOutcome.FOUND_REJECTED, Optional.empty(), List.of());

    ReconcileInvoiceResult result =
        new ReconcileInvoiceUseCase(repository, new NoOpIssuanceAttemptPort(), regime, "CO").execute(businessKey, 0);

    assertEquals(List.of("query"), regime.callOrder);
    assertInstanceOf(ReconcileInvoiceResult.ConfirmedRejected.class, result);
  }

  @Test
  @DisplayName("NOT_FOUND retries issuance, query still strictly first")
  void notFoundRetriesIssuance() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RecordingFiscalRegimePort regime = new RecordingFiscalRegimePort();
    String businessKey = "biz-key-1";
    repository.save(needsReconciliationInvoice(businessKey));
    regime.queryResponse = new RegimeQueryResult(QueryOutcome.NOT_FOUND, Optional.empty(), List.of());
    regime.issueResponse = new IssuanceResult(IssuanceOutcome.ACCEPTED, Optional.of("cufe-retry"), List.of(), "{}");

    ReconcileInvoiceResult result =
        new ReconcileInvoiceUseCase(repository, new NoOpIssuanceAttemptPort(), regime, "CO").execute(businessKey, 0);

    assertEquals(List.of("query", "issue"), regime.callOrder);
    assertInstanceOf(ReconcileInvoiceResult.Retried.class, result);
  }

  @Test
  @DisplayName("AMBIGUOUS never calls issue(), and increments the running count")
  void ambiguousNeverReissues() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RecordingFiscalRegimePort regime = new RecordingFiscalRegimePort();
    String businessKey = "biz-key-1";
    repository.save(needsReconciliationInvoice(businessKey));
    regime.queryResponse = new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());

    ReconcileInvoiceResult result =
        new ReconcileInvoiceUseCase(repository, new NoOpIssuanceAttemptPort(), regime, "CO").execute(businessKey, 1);

    assertEquals(List.of("query"), regime.callOrder);
    assertInstanceOf(ReconcileInvoiceResult.Ambiguous.class, result);
    assertEquals(2, ((ReconcileInvoiceResult.Ambiguous) result).consecutiveAmbiguousCount());
  }

  @Test
  @DisplayName("SRS 9C: the third consecutive ambiguous result moves to MANUAL_REVIEW, which has no automatic exit")
  void thirdConsecutiveAmbiguousMovesToManualReview() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RecordingFiscalRegimePort regime = new RecordingFiscalRegimePort();
    String businessKey = "biz-key-1";
    repository.save(needsReconciliationInvoice(businessKey));
    regime.queryResponse = new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());

    ReconcileInvoiceResult result =
        new ReconcileInvoiceUseCase(repository, new NoOpIssuanceAttemptPort(), regime, "CO").execute(businessKey, 2);

    assertInstanceOf(ReconcileInvoiceResult.MovedToManualReview.class, result);
    assertEquals(DocumentState.MANUAL_REVIEW, ((ReconcileInvoiceResult.MovedToManualReview) result).invoice().state());
    assertTrue(result instanceof ReconcileInvoiceResult.MovedToManualReview);
  }

  @Test
  @DisplayName("a document not in NEEDS_RECONCILIATION is refused without any regime call at all")
  void refusesADocumentNotAwaitingReconciliation() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RecordingFiscalRegimePort regime = new RecordingFiscalRegimePort();
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
    repository.save(draft); // still DRAFT, not NEEDS_RECONCILIATION

    ReconcileInvoiceResult result =
        new ReconcileInvoiceUseCase(repository, new NoOpIssuanceAttemptPort(), regime, "CO").execute("biz-key-1", 0);

    assertInstanceOf(ReconcileInvoiceResult.NotEligible.class, result);
    assertTrue(regime.callOrder.isEmpty(), "no regime call at all for an ineligible document");
  }
}
