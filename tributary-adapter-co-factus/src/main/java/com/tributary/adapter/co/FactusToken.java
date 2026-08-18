package com.tributary.adapter.co;

import java.time.Instant;
import java.util.Objects;

/** An OAuth2 access token from Factus's {@code /oauth/token} (password grant). */
public record FactusToken(String accessToken, String refreshToken, Instant expiresAt) {

  public FactusToken {
    Objects.requireNonNull(accessToken, "accessToken must not be null");
    Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  /**
   * Audit finding: treated as expired {@value #EXPIRY_SKEW_SECONDS}s early on purpose. Without a
   * margin, a token that passes this check with milliseconds left is still in flight when it
   * expires, and Factus answers 401 — which the caller cannot distinguish from a real problem and
   * which, before the accompanying gateway fix, was mapped to the irreversible REJECTED state.
   * The cost of refreshing slightly early is one extra token call; the cost of refreshing slightly
   * late is an issuance that fails for no reason the operator can see.
   */
  boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt.minusSeconds(EXPIRY_SKEW_SECONDS));
  }

  private static final long EXPIRY_SKEW_SECONDS = 60;
}
