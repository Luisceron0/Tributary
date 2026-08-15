package com.tributary.application.port;

import java.util.Objects;
import java.util.Optional;

/**
 * @param accepted whether the regime accepted the correction artifact (RF-004)
 * @param externalReference the regime's identifier for the correction artifact, if it produced one
 * @param rawResponse the unprocessed response, kept for audit
 */
public record CancellationResult(
    boolean accepted, Optional<String> externalReference, String rawResponse) {

  public CancellationResult {
    Objects.requireNonNull(
        externalReference, "externalReference must not be null — use Optional.empty()");
    Objects.requireNonNull(rawResponse, "rawResponse must not be null");
  }
}
