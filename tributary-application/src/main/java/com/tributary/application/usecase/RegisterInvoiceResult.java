package com.tributary.application.usecase;

import com.tributary.domain.Invoice;
import com.tributary.domain.RuleViolation;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link RegisterInvoiceUseCase#execute}, matching RF-001's alternate flows one for
 * one. Sealed so a caller (eventually {@code tributary-api}, mapping to HTTP status codes) is
 * forced by the compiler to handle every case rather than falling through to a default.
 */
public sealed interface RegisterInvoiceResult {

  /** A new draft was persisted. Maps to {@code 201}. */
  record Created(Invoice invoice) implements RegisterInvoiceResult {
    public Created {
      Objects.requireNonNull(invoice, "invoice must not be null");
    }
  }

  /**
   * The businessKey already had a DRAFT with this exact identity — RF-001's idempotency: "two
   * identical requests produce exactly one document." Nothing new was saved; this IS the document
   * from the first call. Maps to {@code 201} as well, not an error — resubmitting the same request
   * is not a mistake.
   */
  record AlreadyDrafted(Invoice existingDraft) implements RegisterInvoiceResult {
    public AlreadyDrafted {
      Objects.requireNonNull(existingDraft, "existingDraft must not be null");
    }
  }

  /**
   * The businessKey already exists but in a state other than DRAFT — the sale was already
   * registered and has moved on. Maps to {@code 409}: a second draft is never created.
   */
  record Conflict(String businessKey) implements RegisterInvoiceResult {
    public Conflict {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
    }
  }

  /** EN 16931 business rules rejected the request; nothing was persisted. Maps to {@code 422}. */
  record Invalid(List<RuleViolation> violations) implements RegisterInvoiceResult {
    public Invalid {
      Objects.requireNonNull(violations, "violations must not be null");
      violations = List.copyOf(violations);
    }
  }
}
