package com.tributary.api.logging;

import java.util.regex.Pattern;

/**
 * T-701: SRS 5.3 — "ningún log contiene identificadores fiscales, tokens ni payloads completos."
 * A regex-based safety net for anything that reaches a log call as free text (an exception
 * message, in particular) — the real, primary control is simpler and stronger: {@link
 * com.tributary.api.web.RequestLoggingFilter} never puts a token, header value or body into a log
 * field to begin with. This exists for the case that discipline misses, not as the only line of
 * defence (SRS 5.3's own words: "modo debug deshabilitado por configuración, no por disciplina" —
 * the same reasoning applied to redaction).
 */
public final class LogRedactor {

  private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
  private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._-]+");

  private LogRedactor() {}

  public static String redact(String message) {
    if (message == null) {
      return null;
    }
    String redacted = BEARER_PATTERN.matcher(message).replaceAll("Bearer [REDACTED]");
    redacted = JWT_PATTERN.matcher(redacted).replaceAll("[REDACTED]");
    return redacted;
  }
}
