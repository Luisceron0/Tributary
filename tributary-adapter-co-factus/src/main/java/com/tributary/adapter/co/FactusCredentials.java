package com.tributary.adapter.co;

import java.util.Objects;

/**
 * Factus sandbox/production connection parameters. Built by the caller from environment
 * variables (T-309's concern, wired in phase 7) — this record never reads {@code
 * System.getenv()} itself, so it stays trivially testable with literal values.
 */
public record FactusCredentials(
    String baseUrl, String clientId, String clientSecret, String username, String password) {

  public FactusCredentials {
    Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    Objects.requireNonNull(clientId, "clientId must not be null");
    Objects.requireNonNull(clientSecret, "clientSecret must not be null");
    Objects.requireNonNull(username, "username must not be null");
    Objects.requireNonNull(password, "password must not be null");
  }

  @Override
  public String toString() {
    // Never let a logging call or a debugger's toString accidentally print a credential
    // (SRS 5.3: client_secret is never logged).
    return "FactusCredentials[baseUrl=%s, clientId=%s, clientSecret=REDACTED, username=%s, password=REDACTED]"
        .formatted(baseUrl, clientId, username);
  }
}
