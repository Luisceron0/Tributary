package com.tributary.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T-900 / SRS §10.5: inbound rate limiting, per IP and per client, answering {@code 429}.
 *
 * <p>This closes a gap the SRS itself had already declared: §6.5's issuance row lists {@code 429
 * limitador propio} as an alternative flow, and until now no code path could produce it —
 * T-301's Factus limiter waits internally rather than rejecting, which protects the upstream
 * quota but never tells the caller anything.
 *
 * <p>One class, registered twice at different points in the chain, because the two limits need
 * different key material and therefore different positions:
 *
 * <ul>
 *   <li><b>By IP</b>, early — before authentication, so an unauthenticated flood is rejected
 *       without spending an RSA signature verification per request.
 *   <li><b>By client</b>, after authentication — keyed on the JWT {@code sub}. It cannot run any
 *       earlier: reading the subject from an unverified token would be trusting attacker-supplied
 *       identity, which this project forbids outright.
 * </ul>
 *
 * <p>In-memory, single-deployment state (SRS 6.2: one deployment, no queues, no shared cache to
 * introduce). Buckets are evicted once idle so the map cannot grow without bound from rotating
 * source addresses.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

  private final Function<HttpServletRequest, Optional<String>> keyExtractor;
  private final int permitsPerMinute;
  private final String limitName;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

  public RateLimitFilter(
      String limitName, int permitsPerMinute, Function<HttpServletRequest, Optional<String>> keyExtractor) {
    this.limitName = limitName;
    this.permitsPerMinute = permitsPerMinute;
    this.keyExtractor = keyExtractor;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Optional<String> key = keyExtractor.apply(request);
    if (key.isEmpty()) {
      // Nothing to key on (e.g. the per-client limit on an unauthenticated request). The other
      // limit still applies — this is not a bypass, it is the wrong filter for this request.
      filterChain.doFilter(request, response);
      return;
    }

    long now = System.currentTimeMillis();
    evictIdle(now);
    Bucket bucket = buckets.computeIfAbsent(key.get(), unused -> new Bucket(permitsPerMinute, now));

    if (!bucket.tryConsume(now, permitsPerMinute)) {
      response.setStatus(429);
      response.setHeader("Retry-After", "60");
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"rate limit exceeded\",\"limit\":\"" + limitName + "\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private void evictIdle(long now) {
    if (buckets.size() < 1024) {
      return; // cheap guard: only sweep once the map is large enough to be worth it
    }
    buckets.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMs() > IDLE_EVICTION.toMillis());
  }

  /** Token bucket: smooths bursts instead of letting a fixed window be gamed at its boundary. */
  private static final class Bucket {
    private double tokens;
    private long lastRefillMs;

    Bucket(int capacity, long now) {
      this.tokens = capacity;
      this.lastRefillMs = now;
    }

    synchronized long lastSeenMs() {
      return lastRefillMs;
    }

    synchronized boolean tryConsume(long now, int permitsPerMinute) {
      double refillPerMs = permitsPerMinute / 60_000.0;
      tokens = Math.min(permitsPerMinute, tokens + (now - lastRefillMs) * refillPerMs);
      lastRefillMs = now;
      if (tokens < 1.0) {
        return false;
      }
      tokens -= 1.0;
      return true;
    }
  }
}
