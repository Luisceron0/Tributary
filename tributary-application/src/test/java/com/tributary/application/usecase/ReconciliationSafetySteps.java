package com.tributary.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Step definitions for {@code reconciliation-safety.feature}. The doubles are hand-rolled and
 * record call ORDER, because one of the scenarios asserts something no return-value check can
 * express: that the regime was asked before any issuance was attempted.
 */
public class ReconciliationSafetySteps {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  private final RecordingRegime regime = new RecordingRegime();
  private final InMemoryInvoices invoices = new InMemoryInvoices();
  private final RecordingAttempts attempts = new RecordingAttempts();

  private String businessKey;
  private int ambiguousSoFar;
  private ReconcileInvoiceResult result;

  private static final class RecordingRegime implements FiscalRegimePort {
    final List<String> callOrder = new ArrayList<>();
    RegimeQueryResult queryResponse;
    IssuanceResult issueResponse =
        new IssuanceResult(IssuanceOutcome.ACCEPTED, Optional.of("REISSUED-1"), List.of(), "retried");

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
    public RegimeQueryResult query(String key) {
      callOrder.add("query");
      return queryResponse;
    }
  }

  private static final class InMemoryInvoices implements InvoiceRepository {
    final Map<String, Invoice> byKey = new HashMap<>();
    final Map<String, UUID> ids = new HashMap<>();

    @Override
    public Optional<Invoice> findByBusinessKey(String key) {
      return Optional.ofNullable(byKey.get(key));
    }

    @Override
    public Optional<UUID> findIdByBusinessKey(String key) {
      return Optional.ofNullable(ids.get(key));
    }

    @Override
    public void save(Invoice invoice) {
      byKey.put(invoice.businessKey(), invoice);
      ids.computeIfAbsent(invoice.businessKey(), k -> UUID.randomUUID());
    }

    @Override
    public long countByBusinessKey(String key) {
      return byKey.containsKey(key) ? 1 : 0;
    }

    @Override
    public boolean tryTransition(String key, DocumentState from, DocumentState to) {
      Invoice current = byKey.get(key);
      if (current == null || current.state() != from) {
        return false;
      }
      byKey.put(key, current.transitionTo(to));
      return true;
    }
  }

  private static final class RecordingAttempts implements IssuanceAttemptPort {
    final List<IssuanceResult> recorded = new ArrayList<>();

    @Override
    public void record(String key, String regimeName, IssuanceResult result) {
      recorded.add(result);
    }
  }

  private ReconcileInvoiceUseCase useCase() {
    return new ReconcileInvoiceUseCase(invoices, attempts, regime, "CO");
  }

  private void reconcile() {
    result = useCase().execute(businessKey, ambiguousSoFar);
  }

  private Invoice stored() {
    return invoices.findByBusinessKey(businessKey).orElseThrow();
  }

  @Given("an invoice {string} awaiting reconciliation")
  public void anInvoiceAwaitingReconciliation(String key) {
    this.businessKey = key;
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1",
            "Widgets",
            Quantity.of("1", "C62"),
            Money.of("100.00", EUR),
            Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(key, ISSUER, BUYER, EUR, LocalDate.of(2026, 3, 1), List.of(line), Money.zero(EUR));
    invoices.save(draft.transitionTo(DocumentState.SUBMITTING).transitionTo(DocumentState.NEEDS_RECONCILIATION));
  }

  @Given("{int} consecutive ambiguous reconciliations have already occurred")
  public void consecutiveAmbiguousReconciliations(int count) {
    this.ambiguousSoFar = count;
  }

  @When("the regime reports the document as validated with reference {string}")
  public void regimeReportsValidated(String reference) {
    regime.queryResponse =
        new RegimeQueryResult(QueryOutcome.FOUND_VALIDATED, Optional.of(reference), List.of());
    reconcile();
  }

  @When("the regime reports the document as validated with reference {string} and notice {string}")
  public void regimeReportsValidatedWithNotice(String reference, String notice) {
    regime.queryResponse =
        new RegimeQueryResult(QueryOutcome.FOUND_VALIDATED, Optional.of(reference), List.of(notice));
    reconcile();
  }

  @When("the regime reports the document as rejected with reason {string}")
  public void regimeReportsRejected(String reason) {
    regime.queryResponse =
        new RegimeQueryResult(QueryOutcome.FOUND_REJECTED, Optional.empty(), List.of(reason));
    reconcile();
  }

  @When("the regime reports the document as not found")
  public void regimeReportsNotFound() {
    regime.queryResponse = new RegimeQueryResult(QueryOutcome.NOT_FOUND, Optional.empty(), List.of());
    reconcile();
  }

  @When("the regime reports the document as ambiguous")
  public void regimeReportsAmbiguous() {
    regime.queryResponse = new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    reconcile();
  }

  @Then("the invoice ends in state {string}")
  public void invoiceEndsInState(String expected) {
    assertEquals(DocumentState.valueOf(expected), stored().state());
  }

  @Then("the invoice remains in state {string}")
  public void invoiceRemainsInState(String expected) {
    assertEquals(DocumentState.valueOf(expected), stored().state());
  }

  @Then("the recorded attempt references {string}")
  public void recordedAttemptReferences(String reference) {
    assertFalse(attempts.recorded.isEmpty(), "no issuance attempt was recorded at all");
    IssuanceResult last = attempts.recorded.get(attempts.recorded.size() - 1);
    assertEquals(reference, last.externalReference().orElseThrow());
  }

  @Then("the recorded attempt carries {int} message")
  public void recordedAttemptCarriesMessages(int count) {
    assertFalse(attempts.recorded.isEmpty(), "no issuance attempt was recorded at all");
    IssuanceResult last = attempts.recorded.get(attempts.recorded.size() - 1);
    assertEquals(
        count,
        last.warnings().size(),
        "the regime's own messages are recorded and never discarded (RF-002)");
  }

  @Then("the regime is asked before any issuance is attempted")
  public void regimeIsAskedFirst() {
    assertEquals(List.of("query", "issue"), regime.callOrder);
  }

  @Then("no issuance is attempted")
  public void noIssuanceAttempted() {
    assertTrue(
        regime.callOrder.stream().noneMatch("issue"::equals),
        "an issuance was attempted on an unresolved document: " + regime.callOrder);
  }
}
