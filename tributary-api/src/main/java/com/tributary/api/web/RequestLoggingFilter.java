package com.tributary.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T-701: one structured line per request — method, path, status, duration, actor (the JWT
 * subject, {@code sub}, T-009's own source of truth for who) — never the {@code Authorization}
 * header, never a request or response body, never any other header. There is no redaction logic
 * to bypass here because nothing sensitive is ever assembled into a log field in the first place;
 * {@link com.tributary.api.logging.LogRedactor} is the separate safety net for free-text fields
 * this filter deliberately does not produce.
 */
public final class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger("tributary.access");

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long start = System.currentTimeMillis();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long durationMs = System.currentTimeMillis() - start;
      MDC.put("http.method", request.getMethod());
      MDC.put("http.path", request.getRequestURI());
      MDC.put("http.status", String.valueOf(response.getStatus()));
      MDC.put("http.duration_ms", String.valueOf(durationMs));
      MDC.put("actor", actorOf(SecurityContextHolder.getContext().getAuthentication()));
      try {
        log.info("request completed");
      } finally {
        MDC.clear();
      }
    }
  }

  private static String actorOf(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return "anonymous";
    }
    Object principal = authentication.getPrincipal();
    // The JWT's own subject claim, T-009's mandated source — never anything from the request body.
    return principal instanceof Jwt jwt ? jwt.getSubject() : "unknown";
  }
}
