package com.tributary.application.usecase;

import com.tributary.application.port.InvoiceRepository;
import com.tributary.domain.Invoice;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A test double for {@link InvoiceRepository}. A real, PostgreSQL-backed implementation is
 * {@code tributary-persistence}'s job (phase 2, T-2xx) — this exists only so {@link
 * RegisterInvoiceUseCase} is unit-testable in phase 1, before that module has anything to test
 * against. Not shipped: it lives under {@code src/test}, not {@code src/main}.
 */
final class InMemoryInvoiceRepository implements InvoiceRepository {

  private final Map<String, Invoice> byBusinessKey = new LinkedHashMap<>();
  private int saveCount = 0;

  @Override
  public Optional<Invoice> findByBusinessKey(String businessKey) {
    return Optional.ofNullable(byBusinessKey.get(businessKey));
  }

  @Override
  public void save(Invoice invoice) {
    Objects.requireNonNull(invoice, "invoice must not be null");
    byBusinessKey.put(invoice.businessKey(), invoice);
    saveCount++;
  }

  @Override
  public long countByBusinessKey(String businessKey) {
    return byBusinessKey.containsKey(businessKey) ? 1 : 0;
  }

  /** Total number of {@link #save} calls across the repository's lifetime, not just distinct keys. */
  int totalSaveCalls() {
    return saveCount;
  }
}
