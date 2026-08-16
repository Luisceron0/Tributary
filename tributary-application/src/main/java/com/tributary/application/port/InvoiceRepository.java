package com.tributary.application.port;

import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import java.util.Optional;
import java.util.UUID;

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

  /**
   * Atomically claims the transition {@code from -> to}: applies it only if the persisted state is
   * currently exactly {@code from}, and reports whether it actually did.
   *
   * <p>Added while building T-308 (20 concurrent callers on the same document must produce exactly
   * one issuance): a plain read-then-{@link #save}, done by 20 threads at once, lets every one of
   * them observe {@code DRAFT} before any of them commits {@code SUBMITTING} — all 20 would then
   * call the regime. This method is what a use case must use for the one transition that guards an
   * irreversible side effect, so only the caller that genuinely wins the race proceeds.
   */
  boolean tryTransition(String businessKey, DocumentState from, DocumentState to);

  /**
   * The persistence-layer surrogate id for {@code businessKey} — never exposed on the domain
   * {@link Invoice} itself (T-106's own established pattern: the domain has no concept of a
   * database row id). Added for T-602-style callers that need to satisfy a foreign key elsewhere
   * (e.g. {@code fiscal_record.invoice_id}) without the application layer ever depending on
   * {@code tributary-persistence} directly.
   */
  Optional<UUID> findIdByBusinessKey(String businessKey);
}
