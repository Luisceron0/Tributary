package com.tributary.adapter.co;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A recorded {@code 429} from Factus (T-302). RF-002: this is a failure of Tributary's own
 * limiter (T-301 should have prevented ever reaching this), not a normal branch of the issuance
 * flow — kept as a distinct, inspectable value rather than folded silently into a retry so
 * calling code and tests can confirm it was recorded, not just survived.
 */
public record FactusRateLimitIncident(Instant occurredAt, Duration serverRequestedRetryAfter, Duration actualWait) {

  public FactusRateLimitIncident {
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(serverRequestedRetryAfter, "serverRequestedRetryAfter must not be null");
    Objects.requireNonNull(actualWait, "actualWait must not be null");
  }
}
