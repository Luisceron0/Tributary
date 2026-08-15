package com.tributary.application.usecase;

import com.tributary.application.port.InvoiceRepository;
import com.tributary.domain.DocumentState;
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
  public synchronized Optional<Invoice> findByBusinessKey(String businessKey) {
    return Optional.ofNullable(byBusinessKey.get(businessKey));
  }

  @Override
  public synchronized void save(Invoice invoice) {
    Objects.requireNonNull(invoice, "invoice must not be null");
    byBusinessKey.put(invoice.businessKey(), invoice);
    saveCount++;
  }

  @Override
  public synchronized long countByBusinessKey(String businessKey) {
    return byBusinessKey.containsKey(businessKey) ? 1 : 0;
  }

  @Override
  public synchronized boolean tryTransition(String businessKey, DocumentState from, DocumentState to) {
    Invoice current = byBusinessKey.get(businessKey);
    if (current == null || current.state() != from) {
      return false;
    }
    byBusinessKey.put(businessKey, current.transitionTo(to));
    saveCount++;
    return true;
  }

  /** Total number of {@link #save} calls across the repository's lifetime, not just distinct keys. */
  synchronized int totalSaveCalls() {
    return saveCount;
  }
}
