package com.tributary.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T-701 correction (see lessons.md): {@code SecurityContextHolderFilter} clears {@link
 * SecurityContextHolder} in its own {@code finally} block as soon as the rest of the chain
 * returns — before control unwinds back to any filter positioned earlier/outer than it, such as
 * {@link com.tributary.api.web.RequestLoggingFilter}. Reading {@code SecurityContextHolder} from
 * that outer filter's own {@code finally} block therefore always sees an already-cleared context,
 * regardless of whether the request was actually authenticated.
 *
 * <p>This filter is positioned {@code addFilterAfter(..., BearerTokenAuthenticationFilter.class)}
 * — after JWT authentication has resolved, before the authorization decision — precisely so it
 * runs while the context is still populated, and stashes the actor into a request attribute.
 * Request attributes are plain fields on the {@code HttpServletRequest} object, not a ThreadLocal,
 * so they survive {@code SecurityContextHolderFilter}'s later clearing untouched.
 */
public final class ActorCaptureFilter extends OncePerRequestFilter {

  public static final String ACTOR_ATTRIBUTE = "tributary.actor";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    request.setAttribute(ACTOR_ATTRIBUTE, actorOf(SecurityContextHolder.getContext().getAuthentication()));
    filterChain.doFilter(request, response);
  }

  private static String actorOf(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return "anonymous";
    }
    Object principal = authentication.getPrincipal();
    return principal instanceof Jwt jwt ? jwt.getSubject() : "unknown";
  }
}
