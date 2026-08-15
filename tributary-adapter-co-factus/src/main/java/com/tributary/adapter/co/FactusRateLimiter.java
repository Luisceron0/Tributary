package com.tributary.adapter.co;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * T-301: a true sliding window, not a fixed bucket that resets on the clock tick — at most {@code
 * maxRequestsPerWindow} permits granted in any trailing {@code window}-long interval, computed
 * from whenever the caller actually asks, not from a fixed reset boundary a fixed-window
 * implementation would allow bursting across.
 *
 * <p>{@link #reserveSlot} is the pure decision function (given "now", when may this caller
 * proceed) — separated from {@link #acquire()}'s actual sleeping so the decision logic is testable
 * against thousands of simulated requests without waiting real minutes.
 */
public final class FactusRateLimiter {

  private final int maxRequestsPerWindow;
  private final Duration window;
  private final Deque<Instant> permits = new ArrayDeque<>();

  public FactusRateLimiter(int maxRequestsPerWindow, Duration window) {
    if (maxRequestsPerWindow <= 0) {
      throw new IllegalArgumentException("maxRequestsPerWindow must be positive: " + maxRequestsPerWindow);
    }
    this.maxRequestsPerWindow = maxRequestsPerWindow;
    this.window = Objects.requireNonNull(window, "window must not be null");
  }

  /** Blocks the calling thread until a permit is available, then consumes it. */
  public void acquire() {
    Instant permitAt = reserveSlot(Instant.now());
    Duration wait = Duration.between(Instant.now(), permitAt);
    if (wait.isPositive()) {
      try {
        Thread.sleep(wait.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for a Factus rate limit permit", e);
      }
    }
  }

  /**
   * Reserves a slot as of {@code now}, returning the instant at which it becomes valid — {@code
   * now} itself if capacity is immediately available, or a later instant if the caller must wait
   * for the oldest permit in the window to age out.
   */
  synchronized Instant reserveSlot(Instant now) {
    Objects.requireNonNull(now, "now must not be null");

    while (!permits.isEmpty() && !permits.peekFirst().isAfter(now.minus(window))) {
      permits.pollFirst();
    }

    if (permits.size() < maxRequestsPerWindow) {
      permits.addLast(now);
      return now;
    }

    Instant oldest = permits.pollFirst();
    Instant availableAt = oldest.plus(window);
    permits.addLast(availableAt);
    return availableAt;
  }
}
