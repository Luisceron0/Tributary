package com.tributary.application.usecase;

import com.tributary.domain.Invoice;
import java.util.Objects;

/** RF-008's four outcomes, matching {@code com.tributary.application.port.QueryOutcome} 1:1. */
public sealed interface ReconcileInvoiceResult {

  /** The regime confirmed it issued the document; its answer was adopted. */
  record Adopted(Invoice invoice) implements ReconcileInvoiceResult {
    public Adopted {
      Objects.requireNonNull(invoice, "invoice must not be null");
    }
  }

  /** The regime confirmed it rejected the document. */
  record ConfirmedRejected(Invoice invoice) implements ReconcileInvoiceResult {
    public ConfirmedRejected {
      Objects.requireNonNull(invoice, "invoice must not be null");
    }
  }

  /** Not found at the regime: issuance was retried (transitioned back through SUBMITTING). */
  record Retried(Invoice invoice) implements ReconcileInvoiceResult {
    public Retried {
      Objects.requireNonNull(invoice, "invoice must not be null");
    }
  }

  /**
   * The regime's answer was ambiguous. {@code consecutiveAmbiguousCount} is this invoice's new
   * running count, for the caller to persist and pass back on the next reconciliation attempt —
   * three consecutive ambiguous results move the document to MANUAL_REVIEW instead (SRS 9C).
   */
  record Ambiguous(String businessKey, int consecutiveAmbiguousCount) implements ReconcileInvoiceResult {
    public Ambiguous {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
    }
  }

  /** No automatic exit from here (SRS 9C) — reached after three consecutive ambiguous results. */
  record MovedToManualReview(Invoice invoice) implements ReconcileInvoiceResult {
    public MovedToManualReview {
      Objects.requireNonNull(invoice, "invoice must not be null");
    }
  }

  record NotEligible(String businessKey, com.tributary.domain.DocumentState actualState) implements ReconcileInvoiceResult {
    public NotEligible {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
      Objects.requireNonNull(actualState, "actualState must not be null");
    }
  }
}
