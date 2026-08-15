package com.tributary.application.usecase;

import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.InvoiceRepository;
import com.tributary.application.port.IssuanceAttemptPort;
import com.tributary.application.port.IssuanceResult;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import java.util.Objects;
import java.util.Optional;

/**
 * RF-002 / T-304: submits a draft invoice to a fiscal regime.
 *
 * <p>The DRAFT -&gt; SUBMITTING transition is saved and returns — a fully separate, already
 * committed step — BEFORE {@link FiscalRegimePort#issue} is ever called. That ordering is the
 * entire point (ADR-003, irreversibility): if the process dies during or after the network call,
 * the SUBMITTING state is already durable and visible to any other connection, so a restart never
 * mistakes "no local record of an attempt" for "no attempt happened." What resumes a document
 * stuck in {@code SUBMITTING} is {@link ReconcileInvoiceUseCase} (RF-008, T-306) — this use case's
 * own precondition is strictly DRAFT, matching RF-002 literally.
 *
 * <p>The claim itself uses {@link InvoiceRepository#tryTransition}, not a plain read-then-{@link
 * InvoiceRepository#save save}: found while building T-308 (20 concurrent callers on the same
 * document must produce exactly one issuance) — a read-then-save lets every one of N concurrent
 * callers observe DRAFT before any of them commits SUBMITTING, and all N would then call the
 * regime. Only the caller for whom {@code tryTransition} actually returns {@code true} proceeds;
 * everyone else treats it as {@link IssueInvoiceResult.InvalidState}, exactly as if they had
 * simply lost a race to a state that was never DRAFT to begin with.
 *
 * <p>The regime's answer is recorded via {@link IssuanceAttemptPort} BEFORE the invoice
 * transitions to ISSUED/ISSUED_WITH_WARNINGS — required by V4's own trigger (T-203), which
 * enforces that order at the database, not just here.
 */
public final class IssueInvoiceUseCase {

  private final InvoiceRepository invoiceRepository;
  private final IssuanceAttemptPort issuanceAttemptPort;
  private final FiscalRegimePort fiscalRegimePort;
  private final String regime;

  public IssueInvoiceUseCase(
      InvoiceRepository invoiceRepository,
      IssuanceAttemptPort issuanceAttemptPort,
      FiscalRegimePort fiscalRegimePort,
      String regime) {
    this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository must not be null");
    this.issuanceAttemptPort = Objects.requireNonNull(issuanceAttemptPort, "issuanceAttemptPort must not be null");
    this.fiscalRegimePort = Objects.requireNonNull(fiscalRegimePort, "fiscalRegimePort must not be null");
    this.regime = Objects.requireNonNull(regime, "regime must not be null");
  }

  public IssueInvoiceResult execute(String businessKey) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");

    // Atomic claim: of any number of concurrent callers for the same businessKey, exactly one
    // gets true here. See class-level note.
    boolean claimed = invoiceRepository.tryTransition(businessKey, DocumentState.DRAFT, DocumentState.SUBMITTING);
    if (!claimed) {
      Optional<Invoice> current = invoiceRepository.findByBusinessKey(businessKey);
      if (current.isEmpty()) {
        return new IssueInvoiceResult.NotFound(businessKey);
      }
      return new IssueInvoiceResult.InvalidState(businessKey, current.orElseThrow().state());
    }

    Invoice submitting = invoiceRepository.findByBusinessKey(businessKey).orElseThrow();

    IssuanceResult issuanceResult = fiscalRegimePort.issue(submitting);

    // Recorded BEFORE the ISSUED/ISSUED_WITH_WARNINGS transition — V4's trigger requires this order.
    issuanceAttemptPort.record(businessKey, regime, issuanceResult);

    DocumentState nextState =
        switch (issuanceResult.outcome()) {
          case ACCEPTED -> DocumentState.ISSUED;
          case ACCEPTED_WITH_WARNINGS -> DocumentState.ISSUED_WITH_WARNINGS;
          case REJECTED -> DocumentState.REJECTED;
          case UNREACHABLE -> DocumentState.NEEDS_RECONCILIATION;
        };

    Invoice finalInvoice = submitting.transitionTo(nextState);
    invoiceRepository.save(finalInvoice);

    return new IssueInvoiceResult.Issued(finalInvoice);
  }
}
