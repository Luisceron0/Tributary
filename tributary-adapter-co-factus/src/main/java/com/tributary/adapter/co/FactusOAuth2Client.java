package com.tributary.adapter.co;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * T-300: caches the current Factus access token, refreshing it on demand. Concurrent callers that
 * all see an expired (or absent) token must trigger exactly ONE refresh — not one per caller — so
 * 20 threads racing to submit at token expiry don't independently hammer {@code /oauth/token}.
 *
 * <p>Double-checked locking: a caller first reads the cached token without any lock (the common
 * case — a valid token — never blocks on anything). Only when it looks expired does the caller
 * take the lock and check AGAIN before actually fetching, because another thread may have already
 * refreshed while this one was waiting for the lock.
 */
public final class FactusOAuth2Client {

  private final Supplier<FactusToken> tokenFetcher;
  private final Supplier<Instant> clock;
  private final Object refreshLock = new Object();

  private volatile FactusToken cachedToken;

  public FactusOAuth2Client(Supplier<FactusToken> tokenFetcher, Supplier<Instant> clock) {
    this.tokenFetcher = Objects.requireNonNull(tokenFetcher, "tokenFetcher must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public FactusToken currentToken() {
    FactusToken token = cachedToken;
    if (isUsable(token)) {
      return token;
    }
    synchronized (refreshLock) {
      token = cachedToken;
      if (isUsable(token)) {
        return token;
      }
      FactusToken fresh = tokenFetcher.get();
      cachedToken = fresh;
      return fresh;
    }
  }

  private boolean isUsable(FactusToken token) {
    return token != null && !token.isExpired(clock.get());
  }
}
