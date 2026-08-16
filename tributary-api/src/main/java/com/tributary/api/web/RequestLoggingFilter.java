package com.tributary.api.web;

import com.tributary.api.security.ActorCaptureFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T-701: one structured line per request — method, path, status, duration, actor (the JWT
 * subject, {@code sub}, T-009's own source of truth for who) — never the {@code Authorization}
 * header, never a request or response body, never any other header. There is no redaction logic
 * to bypass here because nothing sensitive is ever assembled into a log field in the first place;
 * {@link com.tributary.api.logging.LogRedactor} is the separate safety net for free-text fields
 * this filter deliberately does not produce.
 *
 * <p>The actor comes from a request attribute set by {@link ActorCaptureFilter}, not from {@code
 * SecurityContextHolder} directly — see that class's Javadoc and lessons.md for why: by the time
 * this filter's own {@code finally} block runs, Spring's {@code SecurityContextHolderFilter} (positioned
 * deeper in the chain) has already cleared the ThreadLocal in its own {@code finally} block. A request
 * attribute survives that clearing; the ThreadLocal does not.
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
      Object actor = request.getAttribute(ActorCaptureFilter.ACTOR_ATTRIBUTE);
      MDC.put("http.method", request.getMethod());
      MDC.put("http.path", request.getRequestURI());
      MDC.put("http.status", String.valueOf(response.getStatus()));
      MDC.put("http.duration_ms", String.valueOf(durationMs));
      MDC.put("actor", actor instanceof String s ? s : "anonymous");
      try {
        log.info("request completed");
      } finally {
        MDC.clear();
      }
    }
  }
}
