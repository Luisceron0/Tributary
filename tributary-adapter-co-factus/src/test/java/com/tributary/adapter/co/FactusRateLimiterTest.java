package com.tributary.adapter.co;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-301: a sliding window of 60 requests per 60 seconds. {@link FactusRateLimiter#reserveSlot}
 * is the pure decision function (given "now", when may this caller proceed) — tested directly
 * with a fake clock so 200+ requests can be checked without waiting real minutes. {@code
 * acquire()} (the actual sleeping wrapper) is a thin, separately reasoned-about layer on top.
 */
class FactusRateLimiterTest {

  private static final int LIMIT = 60;
  private static final Duration WINDOW = Duration.ofSeconds(60);

  @Test
  @DisplayName("the first 60 requests at the same instant are permitted immediately")
  void firstSixtyRequestsAreImmediate() {
    FactusRateLimiter limiter = new FactusRateLimiter(LIMIT, WINDOW);
    Instant now = Instant.parse("2026-08-15T10:00:00Z");

    for (int i = 0; i < LIMIT; i++) {
      Instant permitAt = limiter.reserveSlot(now);
      assertEquals(now, permitAt, "request " + i + " should be immediate");
    }
  }

  @Test
  @DisplayName("the 61st request at the same instant is deferred by a full window")
  void sixtyFirstRequestIsDeferred() {
    FactusRateLimiter limiter = new FactusRateLimiter(LIMIT, WINDOW);
    Instant now = Instant.parse("2026-08-15T10:00:00Z");

    for (int i = 0; i < LIMIT; i++) {
      limiter.reserveSlot(now);
    }
    Instant permitAt = limiter.reserveSlot(now);

    assertEquals(now.plus(WINDOW), permitAt);
  }

  @Test
  @DisplayName(
      "T-301's literal criterion: across 200 requests, no 60-second window ever contains more than 60 permits")
  void noSixtySecondWindowExceedsTheLimit() {
    FactusRateLimiter limiter = new FactusRateLimiter(LIMIT, WINDOW);
    Instant now = Instant.parse("2026-08-15T10:00:00Z");

    // Worst case: all 200 callers arrive simultaneously and race the limiter at once.
    List<Instant> permits = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      permits.add(limiter.reserveSlot(now));
    }
    permits.sort(Instant::compareTo);

    for (int i = 0; i < permits.size(); i++) {
      Instant windowStart = permits.get(i);
      Instant windowEnd = windowStart.plus(WINDOW);
      long countInWindow = permits.stream().filter(p -> !p.isBefore(windowStart) && p.isBefore(windowEnd)).count();
      long finalI = i;
      assertTrue(
          countInWindow <= LIMIT,
          () -> "window starting at permit " + finalI + " (" + windowStart + ") contains " + countInWindow + " permits, limit is " + LIMIT);
    }

    // Sanity: 200 requests need at least ceil(200/60) - 1 = 3 full extra windows beyond the first.
    Duration span = Duration.between(permits.get(0), permits.get(permits.size() - 1));
    assertFalse(span.compareTo(WINDOW.multipliedBy(3)) < 0, "200 requests at 60/window must span at least 3 windows");
  }

  @Test
  @DisplayName("capacity that has aged out of the window is reclaimed")
  void agedOutCapacityIsReclaimed() {
    FactusRateLimiter limiter = new FactusRateLimiter(LIMIT, WINDOW);
    Instant now = Instant.parse("2026-08-15T10:00:00Z");

    for (int i = 0; i < LIMIT; i++) {
      limiter.reserveSlot(now);
    }
    // A full window later, all 60 earlier slots have aged out — this must be immediate again.
    Instant later = now.plus(WINDOW).plusSeconds(1);
    Instant permitAt = limiter.reserveSlot(later);

    assertEquals(later, permitAt);
  }
}
