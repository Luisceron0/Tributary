package com.tributary.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Audit finding: the bucket map was bounded only by an idle sweep that removed entries unseen for
 * ten minutes, and that sweep was skipped entirely below 1024 entries. Neither condition bounds the
 * map during a flood from rotating source addresses — every bucket is freshly seen, so nothing is
 * evictable, and the map grows with the number of distinct IPs an attacker cares to use.
 *
 * <p>That inverts the filter's purpose. A control installed to keep a public deployment available
 * became the cheapest way to exhaust its memory: no authentication is required to reach it (the
 * per-IP limit runs before the JWT filter, by design), and each forged source address costs the
 * attacker nothing while costing the server a permanent map entry. The point matters more now that
 * the deployment target under consideration is a 1 GB free-tier VM sharing that heap with
 * PostgreSQL and the JVM itself.
 *
 * <p>The fix is a hard ceiling with least-recently-seen eviction, so the map's size is a property
 * of the configuration rather than of the traffic.
 */
class RateLimitFilterMemoryTest {

  private static final int GENEROUS_LIMIT = 1_000_000;

  private static HttpServletRequest requestFrom(String ip) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(ip);
    return request;
  }

  @Test
  @DisplayName("a flood from rotating source addresses cannot grow the bucket map without bound")
  void bucketMapIsBoundedUnderAddressRotation() throws Exception {
    RateLimitFilter filter =
        new RateLimitFilter(
            "per-ip", GENEROUS_LIMIT, request -> Optional.of(request.getRemoteAddr()));
    FilterChain chain = Mockito.mock(FilterChain.class);

    // Every address is distinct and every bucket is freshly seen, so the idle sweep has nothing to
    // collect — precisely the case the old guard did not cover.
    for (int i = 0; i < RateLimitFilter.MAX_TRACKED_KEYS * 3; i++) {
      filter.doFilter(
          requestFrom("10." + (i / 65536 % 256) + "." + (i / 256 % 256) + "." + (i % 256)),
          new MockHttpServletResponse(),
          chain);
    }

    assertTrue(
        filter.trackedKeyCount() <= RateLimitFilter.MAX_TRACKED_KEYS,
        "bucket map grew to "
            + filter.trackedKeyCount()
            + ", above the ceiling of "
            + RateLimitFilter.MAX_TRACKED_KEYS
            + " — an unauthenticated flood can still exhaust memory");
  }

  @Test
  @DisplayName("bounding the map does not weaken the limit itself for an active caller")
  void anActiveCallerIsStillLimited() throws Exception {
    RateLimitFilter filter =
        new RateLimitFilter("per-ip", 3, request -> Optional.of(request.getRemoteAddr()));
    FilterChain chain = Mockito.mock(FilterChain.class);

    int rejected = 0;
    for (int i = 0; i < 10; i++) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(requestFrom("203.0.113.7"), response, chain);
      if (response.getStatus() == 429) {
        rejected++;
      }
    }

    assertEquals(7, rejected, "three permits should be granted and the remaining seven refused");
  }
}
