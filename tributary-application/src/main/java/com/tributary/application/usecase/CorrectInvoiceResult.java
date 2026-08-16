package com.tributary.application.usecase;

import com.tributary.domain.DocumentState;
import java.util.Objects;

/** The outcome of {@link CorrectInvoiceUseCase#correct} — RF-004. */
public sealed interface CorrectInvoiceResult {

  record Corrected(String businessKey, String correctionReference) implements CorrectInvoiceResult {
    public Corrected {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
      Objects.requireNonNull(correctionReference, "correctionReference must not be null");
    }
  }

  record NotFound(String businessKey) implements CorrectInvoiceResult {
    public NotFound {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
    }
  }

  /** RF-004's precondition: only ISSUED or ISSUED_WITH_WARNINGS can be corrected. */
  record InvalidState(String businessKey, DocumentState actualState) implements CorrectInvoiceResult {
    public InvalidState {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
      Objects.requireNonNull(actualState, "actualState must not be null");
    }
  }

  /** RF-004's own alternative flow: already corrected (or any other regime refusal) -> 409, never a silent retry. */
  record RegimeRefused(String businessKey, String rawResponse) implements CorrectInvoiceResult {
    public RegimeRefused {
      Objects.requireNonNull(businessKey, "businessKey must not be null");
      Objects.requireNonNull(rawResponse, "rawResponse must not be null");
    }
  }
}
