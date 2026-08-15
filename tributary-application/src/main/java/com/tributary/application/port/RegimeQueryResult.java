package com.tributary.application.port;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @param outcome what the regime reported
 * @param externalReference the regime's identifier for the document, when found
 * @param warnings non-fatal messages the regime returned
 */
public record RegimeQueryResult(
    QueryOutcome outcome, Optional<String> externalReference, List<String> warnings) {

  public RegimeQueryResult {
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(
        externalReference, "externalReference must not be null — use Optional.empty()");
    Objects.requireNonNull(warnings, "warnings must not be null");
    warnings = List.copyOf(warnings);
  }
}
