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

  /**
   * Hard ceiling on distinct keys held at once. Audit finding: idle eviction alone does not bound
   * this map — during a flood from rotating source addresses every bucket is freshly seen, so
   * nothing is idle and nothing is collected, and the map grows with the attacker's address pool.
   * The per-IP limit deliberately runs before authentication, so reaching it costs an attacker no
   * credentials at all. With a ceiling, the map's size is a property of this configuration rather
   * than of the traffic; the cost of the bound is that a very large legitimate client population
   * evicts each other's buckets, which loses limiter accuracy but never availability.
   */
  static final int MAX_TRACKED_KEYS = 10_000;

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
    Bucket bucket = buckets.computeIfAbsent(key.get(), unused -> new Bucket(permitsPerMinute, now));
    // Swept after the insert, not before: sweeping first leaves room for exactly one more entry and
    // the map settles one above the ceiling. The bucket just created is the most recently seen, so
    // least-recently-seen eviction never discards the request currently being served.
    evictIdle(now);

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

    // Idle eviction is not a bound: under address rotation nothing is idle yet. Drop the
    // least-recently-seen keys until the map is back under its ceiling, so memory use stays a
    // function of configuration rather than of how many addresses a caller chooses to forge.
    if (buckets.size() <= MAX_TRACKED_KEYS) {
      return;
    }
    buckets.entrySet().stream()
        .sorted(java.util.Comparator.comparingLong(entry -> entry.getValue().lastSeenMs()))
        .limit(buckets.size() - (long) MAX_TRACKED_KEYS)
        .map(Map.Entry::getKey)
        .toList()
        .forEach(buckets::remove);
  }

  /** Exposed for the memory-bound test — the map is otherwise entirely internal. */
  int trackedKeyCount() {
    return buckets.size();
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
