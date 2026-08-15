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

  boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
