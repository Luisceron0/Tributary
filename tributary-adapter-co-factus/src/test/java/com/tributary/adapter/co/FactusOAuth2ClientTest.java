package com.tributary.adapter.co;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-300: 20 threads with an expired token must trigger exactly one refresh call. This test
 * exercises the single-flight LOGIC against a fake token fetcher (no real HTTP) so it stays fast
 * and deterministic; {@link FactusAuthGatewayContractTest} proves the real HTTP shape separately
 * against WireMock.
 */
class FactusOAuth2ClientTest {

  private static FactusToken tokenValidFor(Duration duration, Instant now) {
    return new FactusToken("access-" + now, "refresh-" + now, now.plus(duration));
  }

  @Test
  @DisplayName("a valid cached token is reused without calling the fetcher again")
  void reusesAValidCachedToken() {
    AtomicInteger fetchCount = new AtomicInteger();
    Instant now = Instant.parse("2026-08-15T10:00:00Z");
    FactusOAuth2Client client =
        new FactusOAuth2Client(
            () -> {
              fetchCount.incrementAndGet();
              return tokenValidFor(Duration.ofHours(1), now);
            },
            () -> now);

    FactusToken first = client.currentToken();
    FactusToken second = client.currentToken();

    assertSame(first, second);
    assertEquals(1, fetchCount.get());
  }

  @Test
  @DisplayName("an expired token triggers exactly one refresh, not a refresh per call")
  void refreshesOnceWhenExpired() {
    AtomicInteger fetchCount = new AtomicInteger();
    Instant start = Instant.parse("2026-08-15T10:00:00Z");
    java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(start);
    FactusOAuth2Client client =
        new FactusOAuth2Client(
            () -> {
              fetchCount.incrementAndGet();
              return tokenValidFor(Duration.ofMinutes(15), now.get());
            },
            now::get);

    FactusToken first = client.currentToken();
    now.set(start.plus(Duration.ofMinutes(16))); // past expiry
    FactusToken second = client.currentToken();

    assertEquals(2, fetchCount.get());
    assertTrue(second.expiresAt().isAfter(first.expiresAt()), "the refreshed token must be the newer one");
  }

  @Test
  @DisplayName("T-300's literal criterion: 20 threads with an expired token trigger exactly one refresh")
  void twentyThreadsWithExpiredTokenTriggerExactlyOneRefresh() throws InterruptedException {
    AtomicInteger fetchCount = new AtomicInteger();
    Instant now = Instant.parse("2026-08-15T10:00:00Z");
    FactusOAuth2Client client =
        new FactusOAuth2Client(
            () -> {
              fetchCount.incrementAndGet();
              // A deliberate delay: without a real lock, this widens the race window and makes
              // concurrent callers far more likely to all see "no valid token yet" simultaneously.
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return tokenValidFor(Duration.ofHours(1), now);
            },
            () -> now);

    int threadCount = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      pool.submit(
          () -> {
            ready.countDown();
            try {
              start.await(10, TimeUnit.SECONDS);
              client.currentToken();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }

    ready.await(10, TimeUnit.SECONDS);
    start.countDown();
    boolean completed = done.await(10, TimeUnit.SECONDS);
    pool.shutdown();

    org.junit.jupiter.api.Assertions.assertTrue(completed, "all threads must finish within the timeout");
    assertEquals(1, fetchCount.get(), "exactly one refresh call for 20 concurrent callers with no valid token");
  }
}
