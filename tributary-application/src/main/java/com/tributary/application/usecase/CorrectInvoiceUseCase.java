package com.tributary.application.usecase;

import com.tributary.application.port.AuditEventPort;
import com.tributary.application.port.CancellationResult;
import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.InvoiceRepository;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import java.util.Objects;
import java.util.Optional;

/**
 * RF-004: an emitted document is never edited — correction is always a new artifact the regime
 * produces (a CO credit note, an ES anulación record) that references the original, never a
 * mutation of it. {@code invoice.state} is deliberately left untouched on success — RF-004's own
 * postcondition, "el original conserva su estado" — {@link FiscalRegimePort#cancel} is where the
 * actual correction artifact gets created; this use case's only job is the precondition check and
 * the audit trail.
 *
 * <p>Which regime already knows "was this already corrected" (RF-004's own 409 alternative flow)
 * is left to {@link FiscalRegimePort#cancel} itself — each regime represents that differently
 * (ES: an existing ANULACIÓN record; CO: not yet implemented at all), so this use case stays
 * regime-agnostic and just trusts {@link CancellationResult#accepted()}.
 */
public final class CorrectInvoiceUseCase {

  private final InvoiceRepository invoiceRepository;
  private final FiscalRegimePort fiscalRegimePort;
  private final AuditEventPort auditLog;

  public CorrectInvoiceUseCase(InvoiceRepository invoiceRepository, FiscalRegimePort fiscalRegimePort, AuditEventPort auditLog) {
    this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository must not be null");
    this.fiscalRegimePort = Objects.requireNonNull(fiscalRegimePort, "fiscalRegimePort must not be null");
    this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
  }

  public CorrectInvoiceResult correct(String businessKey, String correctionReason, String actor) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");
    Objects.requireNonNull(correctionReason, "correctionReason must not be null");
    Objects.requireNonNull(actor, "actor must not be null");

    Optional<Invoice> found = invoiceRepository.findByBusinessKey(businessKey);
    if (found.isEmpty()) {
      return new CorrectInvoiceResult.NotFound(businessKey);
    }

    Invoice invoice = found.orElseThrow();
    if (invoice.state() != DocumentState.ISSUED && invoice.state() != DocumentState.ISSUED_WITH_WARNINGS) {
      return new CorrectInvoiceResult.InvalidState(businessKey, invoice.state());
    }

    CancellationResult result = fiscalRegimePort.cancel(invoice, correctionReason);

    if (!result.accepted()) {
      auditLog.record(actor, "CORRECT_INVOICE", "invoice:" + businessKey, "DENIED");
      return new CorrectInvoiceResult.RegimeRefused(businessKey, result.rawResponse());
    }

    auditLog.record(actor, "CORRECT_INVOICE", "invoice:" + businessKey, "SUCCESS");
    return new CorrectInvoiceResult.Corrected(businessKey, result.externalReference().orElse(""));
  }
}
