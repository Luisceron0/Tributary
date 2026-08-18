package com.tributary.application.usecase;

import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.InvoiceRepository;
import com.tributary.application.port.IssuanceAttemptPort;
import com.tributary.application.port.IssuanceOutcome;
import com.tributary.application.port.IssuanceResult;
import com.tributary.application.port.RegimeQueryResult;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import java.util.Objects;

/**
 * RF-008 / T-306: reconciles a document stuck in {@code NEEDS_RECONCILIATION}. The regime is
 * ALWAYS queried first — there is no code path here that calls {@link FiscalRegimePort#issue}
 * without a preceding {@link FiscalRegimePort#query} for the same invocation. A lost response
 * means unknown state, not failed state (ADR-003); guessing by retrying blindly is exactly the
 * mistake this class exists to prevent.
 *
 * <p>Three consecutive ambiguous results move the document to {@code MANUAL_REVIEW}, which has no
 * automatic exit (SRS 9C). Persisting the running ambiguous count across separate reconciliation
 * attempts is the caller's responsibility — {@link ReconcileInvoiceResult.Ambiguous} hands back
 * the new count for exactly that purpose; this class has no scheduling or storage concept of its
 * own to keep it in.
 */
public final class ReconcileInvoiceUseCase {

  private final InvoiceRepository invoiceRepository;
  private final IssuanceAttemptPort issuanceAttemptPort;
  private final FiscalRegimePort fiscalRegimePort;
  private final String regime;

  public ReconcileInvoiceUseCase(
      InvoiceRepository invoiceRepository,
      IssuanceAttemptPort issuanceAttemptPort,
      FiscalRegimePort fiscalRegimePort,
      String regime) {
    this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository must not be null");
    this.issuanceAttemptPort = Objects.requireNonNull(issuanceAttemptPort, "issuanceAttemptPort must not be null");
    this.fiscalRegimePort = Objects.requireNonNull(fiscalRegimePort, "fiscalRegimePort must not be null");
    this.regime = Objects.requireNonNull(regime, "regime must not be null");
  }

  public ReconcileInvoiceResult execute(String businessKey, int consecutiveAmbiguousSoFar) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");

    Invoice invoice =
        invoiceRepository
            .findByBusinessKey(businessKey)
            .orElseThrow(() -> new IllegalArgumentException("no invoice for businessKey " + businessKey));
    if (invoice.state() != DocumentState.NEEDS_RECONCILIATION) {
      return new ReconcileInvoiceResult.NotEligible(businessKey, invoice.state());
    }

    // The query, always first — the whole point of this class.
    RegimeQueryResult queryResult = fiscalRegimePort.query(businessKey);

    return switch (queryResult.outcome()) {
      case FOUND_VALIDATED -> adopt(invoice, queryResult);
      case FOUND_REJECTED -> confirmRejected(invoice, queryResult);
      case NOT_FOUND -> retryIssuance(invoice);
      case AMBIGUOUS -> handleAmbiguous(invoice, consecutiveAmbiguousSoFar);
    };
  }

  private ReconcileInvoiceResult adopt(Invoice invoice, RegimeQueryResult queryResult) {
    IssuanceOutcome outcome =
        queryResult.warnings().isEmpty() ? IssuanceOutcome.ACCEPTED : IssuanceOutcome.ACCEPTED_WITH_WARNINGS;
    issuanceAttemptPort.record(
        invoice.businessKey(),
        regime,
        new IssuanceResult(outcome, queryResult.externalReference(), queryResult.warnings(), "reconciled: adopted"));

    DocumentState next = outcome == IssuanceOutcome.ACCEPTED ? DocumentState.ISSUED : DocumentState.ISSUED_WITH_WARNINGS;
    Invoice updated = invoice.transitionTo(next);
    invoiceRepository.save(updated);
    return new ReconcileInvoiceResult.Adopted(updated);
  }

  private ReconcileInvoiceResult confirmRejected(Invoice invoice, RegimeQueryResult queryResult) {
    // The reasons the regime gave are the whole value of this record: REJECTED is terminal, so this
    // attempt row is the last thing ever written about the document. Recording it with an empty
    // message list — as this did before an audit caught it — leaves an operator with a permanently
    // dead invoice and no statement of why (RF-002: the regime's messages are never discarded).
    issuanceAttemptPort.record(
        invoice.businessKey(),
        regime,
        new IssuanceResult(
            IssuanceOutcome.REJECTED, java.util.Optional.empty(), queryResult.warnings(), "reconciled: confirmed rejected"));
    Invoice updated = invoice.transitionTo(DocumentState.REJECTED);
    invoiceRepository.save(updated);
    return new ReconcileInvoiceResult.ConfirmedRejected(updated);
  }

  private ReconcileInvoiceResult retryIssuance(Invoice invoice) {
    Invoice submitting = invoice.transitionTo(DocumentState.SUBMITTING);
    invoiceRepository.save(submitting);

    IssuanceResult issuanceResult = fiscalRegimePort.issue(submitting);
    issuanceAttemptPort.record(invoice.businessKey(), regime, issuanceResult);

    DocumentState next =
        switch (issuanceResult.outcome()) {
          case ACCEPTED -> DocumentState.ISSUED;
          case ACCEPTED_WITH_WARNINGS -> DocumentState.ISSUED_WITH_WARNINGS;
          case REJECTED -> DocumentState.REJECTED;
          case UNREACHABLE -> DocumentState.NEEDS_RECONCILIATION;
        };
    Invoice updated = submitting.transitionTo(next);
    invoiceRepository.save(updated);
    return new ReconcileInvoiceResult.Retried(updated);
  }

  private ReconcileInvoiceResult handleAmbiguous(Invoice invoice, int consecutiveAmbiguousSoFar) {
    int newCount = consecutiveAmbiguousSoFar + 1;
    if (newCount >= 3) {
      Invoice updated = invoice.transitionTo(DocumentState.MANUAL_REVIEW);
      invoiceRepository.save(updated);
      return new ReconcileInvoiceResult.MovedToManualReview(updated);
    }
    return new ReconcileInvoiceResult.Ambiguous(invoice.businessKey(), newCount);
  }
}
