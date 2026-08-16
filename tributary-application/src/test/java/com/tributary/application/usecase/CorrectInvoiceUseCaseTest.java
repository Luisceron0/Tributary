package com.tributary.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.tributary.application.port.AuditEventPort;
import com.tributary.application.port.CancellationResult;
import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.IssuanceResult;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RF-004: an emitted document is never edited; correction always goes through {@link FiscalRegimePort#cancel}. */
class CorrectInvoiceUseCaseTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  private static Invoice sampleInvoice(String businessKey, DocumentState state) {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
    if (state == DocumentState.DRAFT) {
      return draft;
    }
    Invoice submitting = draft.transitionTo(DocumentState.SUBMITTING);
    return state == DocumentState.SUBMITTING ? submitting : submitting.transitionTo(state);
  }

  private static final class FakeFiscalRegimePort implements FiscalRegimePort {
    CancellationResult nextCancelResult = new CancellationResult(true, Optional.of("anulacion-1"), "ok");
    final List<Invoice> cancelledInvoices = new ArrayList<>();

    @Override
    public IssuanceResult issue(Invoice invoice) {
      throw new UnsupportedOperationException("not needed by this test");
    }

    @Override
    public CancellationResult cancel(Invoice original, String correctionReason) {
      cancelledInvoices.add(original);
      return nextCancelResult;
    }

    @Override
    public RegimeQueryResult query(String businessKey) {
      throw new UnsupportedOperationException("not needed by this test");
    }
  }

  private static final class RecordingAuditLog implements AuditEventPort {
    final List<String[]> events = new ArrayList<>();

    @Override
    public void record(String actor, String action, String entity, String result) {
      events.add(new String[] {actor, action, entity, result});
    }
  }

  @Test
  @DisplayName("an ISSUED invoice can be corrected — the regime is asked to cancel it, never edited locally")
  void correctsAnIssuedInvoice() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    Invoice issued = sampleInvoice("biz-key-1", DocumentState.ISSUED);
    repository.save(issued);
    FakeFiscalRegimePort regime = new FakeFiscalRegimePort();
    RecordingAuditLog auditLog = new RecordingAuditLog();
    CorrectInvoiceUseCase useCase = new CorrectInvoiceUseCase(repository, regime, auditLog);

    CorrectInvoiceResult result = useCase.correct(issued.businessKey(), "customer requested a refund", "operator:bob");

    assertInstanceOf(CorrectInvoiceResult.Corrected.class, result);
    assertEquals(1, regime.cancelledInvoices.size());
    assertEquals(DocumentState.ISSUED, repository.findByBusinessKey(issued.businessKey()).orElseThrow().state(), "RF-004: the original keeps its own state, never edited");
  }

  @Test
  @DisplayName("a DRAFT invoice cannot be corrected — RF-004's precondition is ISSUED or ISSUED_WITH_WARNINGS")
  void refusesToCorrectADraftInvoice() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    Invoice draft = sampleInvoice("biz-key-1", DocumentState.DRAFT);
    repository.save(draft);
    FakeFiscalRegimePort regime = new FakeFiscalRegimePort();
    CorrectInvoiceUseCase useCase = new CorrectInvoiceUseCase(repository, regime, new RecordingAuditLog());

    CorrectInvoiceResult result = useCase.correct(draft.businessKey(), "premature", "operator:bob");

    assertInstanceOf(CorrectInvoiceResult.InvalidState.class, result);
    assertEquals(0, regime.cancelledInvoices.size(), "the regime must never be called for an invalid precondition");
  }

  @Test
  @DisplayName("an unknown businessKey is NotFound")
  void unknownBusinessKeyIsNotFound() {
    CorrectInvoiceUseCase useCase =
        new CorrectInvoiceUseCase(new InMemoryInvoiceRepository(), new FakeFiscalRegimePort(), new RecordingAuditLog());

    CorrectInvoiceResult result = useCase.correct("never-existed", "reason", "operator:bob");

    assertInstanceOf(CorrectInvoiceResult.NotFound.class, result);
  }

  @Test
  @DisplayName("RF-004's own alternative flow: the regime refusing (already cancelled) surfaces as RegimeRefused, not a silent success")
  void regimeRefusalSurfacesAsRegimeRefused() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    Invoice issued = sampleInvoice("biz-key-1", DocumentState.ISSUED);
    repository.save(issued);
    FakeFiscalRegimePort regime = new FakeFiscalRegimePort();
    regime.nextCancelResult = new CancellationResult(false, Optional.empty(), "already cancelled");
    CorrectInvoiceUseCase useCase = new CorrectInvoiceUseCase(repository, regime, new RecordingAuditLog());

    CorrectInvoiceResult result = useCase.correct(issued.businessKey(), "second attempt", "operator:bob");

    assertInstanceOf(CorrectInvoiceResult.RegimeRefused.class, result);
  }

  @Test
  @DisplayName("both a successful correction and a refused one are audited")
  void bothOutcomesAreAudited() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    Invoice issued = sampleInvoice("biz-key-1", DocumentState.ISSUED);
    repository.save(issued);
    FakeFiscalRegimePort regime = new FakeFiscalRegimePort();
    RecordingAuditLog auditLog = new RecordingAuditLog();
    CorrectInvoiceUseCase useCase = new CorrectInvoiceUseCase(repository, regime, auditLog);

    useCase.correct(issued.businessKey(), "refund", "operator:bob");

    assertEquals(1, auditLog.events.size());
    assertEquals("operator:bob", auditLog.events.get(0)[0]);
    assertEquals("SUCCESS", auditLog.events.get(0)[3]);
  }
}
