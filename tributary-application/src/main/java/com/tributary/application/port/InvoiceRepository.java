package com.tributary.application.port;

import com.tributary.domain.Invoice;
import java.util.Optional;

/**
 * Persistence port for invoices. A real, PostgreSQL-backed implementation with the chain triggers
 * and unique index that make idempotency binding is {@code tributary-persistence}'s job (phase 2,
 * T-2xx). This port is what {@code tributary-application}'s use cases depend on instead — ADR-001's
 * dependency direction applied to persistence: the use case does not know Spring Data exists.
 */
public interface InvoiceRepository {

  Optional<Invoice> findByBusinessKey(String businessKey);

  void save(Invoice invoice);

  /** The literal "count in the repository" RF-001's acceptance criterion verifies against. */
  long countByBusinessKey(String businessKey);
}
