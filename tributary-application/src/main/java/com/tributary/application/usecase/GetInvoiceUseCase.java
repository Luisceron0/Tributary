package com.tributary.application.usecase;

import com.tributary.application.port.InvoiceRepository;
import com.tributary.domain.Invoice;
import java.util.Objects;
import java.util.Optional;

/** {@code GET /api/v1/invoices/{id}} — {@code id} is the businessKey, the same identifier every other invoice endpoint uses. */
public final class GetInvoiceUseCase {

  private final InvoiceRepository invoiceRepository;

  public GetInvoiceUseCase(InvoiceRepository invoiceRepository) {
    this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository must not be null");
  }

  public Optional<Invoice> execute(String businessKey) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");
    return invoiceRepository.findByBusinessKey(businessKey);
  }
}
