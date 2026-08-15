package com.tributary.application.usecase;

import com.tributary.domain.Invoice;
import java.util.Objects;

/** The outcome of {@link IssueInvoiceUseCase#execute} — always a terminal-for-now {@link Invoice}. */
public sealed interface IssueInvoiceResult {

  record Issued(Invoice invoice) implements IssueInvoiceResult {
    public Issued {
      Objects.requireNonNull(invoice, "invoice must not be null");
    }
  }

  /** No DRAFT (or SUBMITTING left over from a prior crash — see T-307/T-308) invoice for this businessKey. */
  record NotFound(String businessKey) implements IssueInvoiceResult {
    public NotFound {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
    }
  }

  /** The invoice exists but is not in a state issuance can start from (SRS: 409). */
  record InvalidState(String businessKey, com.tributary.domain.DocumentState actualState) implements IssueInvoiceResult {
    public InvalidState {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
      Objects.requireNonNull(actualState, "actualState must not be null");
    }
  }
}
