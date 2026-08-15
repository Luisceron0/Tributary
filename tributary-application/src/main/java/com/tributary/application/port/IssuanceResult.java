package com.tributary.application.port;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @param outcome what happened
 * @param externalReference the regime's own identifier for the issued document — a CUFE for CO, a
 *     chain position for ES, nothing for DE. Named generically on purpose: this type must never
 *     grow a field named after one regime's artifact (ADR-001).
 * @param warnings non-fatal messages the regime returned. Never discarded — RF-002: "las
 *     advertencias de la DIAN se registran y no se descartan."
 * @param rawResponse the unprocessed response, kept for audit and reconciliation
 */
public record IssuanceResult(
    IssuanceOutcome outcome,
    Optional<String> externalReference,
    List<String> warnings,
    String rawResponse) {

  public IssuanceResult {
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(
        externalReference, "externalReference must not be null — use Optional.empty()");
    Objects.requireNonNull(warnings, "warnings must not be null");
    warnings = List.copyOf(warnings);
    Objects.requireNonNull(rawResponse, "rawResponse must not be null");
  }
}
