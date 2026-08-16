package com.tributary.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SRS 5.3: "{@code Host} se valida contra una allowlist explícita." {@code Host} is untrusted
 * input like every other header (T-006) — a request claiming a {@code Host} this deployment
 * never declared is rejected before it reaches any controller or the security filter chain's own
 * authentication step, not merely logged.
 */
public final class HostAllowlistFilter extends OncePerRequestFilter {

  private final Set<String> allowedHosts;

  public HostAllowlistFilter(Set<String> allowedHosts) {
    this.allowedHosts = Set.copyOf(allowedHosts);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String host = request.getHeader("Host");
    String hostWithoutPort = host == null ? null : host.split(":", 2)[0];
    if (hostWithoutPort == null || !allowedHosts.contains(hostWithoutPort)) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Host not recognised");
      return;
    }
    filterChain.doFilter(request, response);
  }
}
