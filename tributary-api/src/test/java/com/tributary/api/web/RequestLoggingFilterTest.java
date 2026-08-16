package com.tributary.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tributary.api.security.ActorCaptureFilter;
import jakarta.servlet.FilterChain;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * T-701: proves, against the filter's actual rendered log output (not just by reading the source),
 * that a real bearer token attached to the request never reaches the {@code "tributary.access"}
 * logger — neither in the formatted message nor in any MDC field. A {@link ListAppender} attached
 * directly to the logback logger captures exactly what would have been written to the console.
 *
 * <p>This is a unit test of {@link RequestLoggingFilter} in isolation: it sets the {@link
 * ActorCaptureFilter#ACTOR_ATTRIBUTE} request attribute directly, the way {@link ActorCaptureFilter}
 * would in the real, fully-wired chain, rather than going through Spring Security itself. It does
 * NOT prove the two filters cooperate correctly when actually wired together in {@code
 * SecurityConfig} — that gap is exactly what let the original "always anonymous" bug (lessons.md)
 * through undetected; {@code ActorLoggingIntegrationTest} covers the real, wired chain instead.
 */
class RequestLoggingFilterTest {

  private static final String LOGGER_NAME = "tributary.access";
  // Synthetic fixture, not a real token: the final segment is a literal marker, not a base64url
  // signature, so this can never be mistaken for (or collide with) an actually-leaked credential.
  private static final String SECRET_TOKEN =
      "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJvcGVyYXRvcjphbGljZSJ9.NOT-A-REAL-SECRET-TEST-FIXTURE-ONLY";

  private final RequestLoggingFilter filter = new RequestLoggingFilter();
  private ListAppender<ILoggingEvent> appender;
  private Logger accessLogger;

  @BeforeEach
  void attachAppender() {
    accessLogger = (Logger) LoggerFactory.getLogger(LOGGER_NAME);
    appender = new ListAppender<>();
    appender.start();
    accessLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    accessLogger.detachAppender(appender);
  }

  @Test
  void logsOneLineWithoutTheAuthorizationHeaderOrAnyOtherHeader() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/invoices");
    request.addHeader("Authorization", "Bearer " + SECRET_TOKEN);
    request.addHeader("X-Forwarded-For", "203.0.113.7");
    request.setContent("{\"taxIdentifier\":\"ESB12345678\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(201);
    FilterChain noopChain = (req, res) -> {};

    filter.doFilterInternal(request, response, noopChain);

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    String rendered = event.getFormattedMessage();
    Map<String, String> mdc = event.getMDCPropertyMap();

    // The actual assertion that matters: the secret never appears anywhere in what was logged,
    // not the message, not any MDC value.
    assertThat(rendered).doesNotContain(SECRET_TOKEN).doesNotContain("Bearer");
    assertThat(mdc.values()).noneMatch(value -> value.contains(SECRET_TOKEN));
    assertThat(mdc).doesNotContainKey("Authorization");
    // Only the deliberate fields exist — no header sneaked in as an MDC entry under another key.
    assertThat(mdc.keySet())
        .containsExactlyInAnyOrder("http.method", "http.path", "http.status", "http.duration_ms", "actor");
  }

  @Test
  void logsMethodPathStatusAndActorFromTheCapturedAttribute() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/invoices/rc1-1");
    request.setAttribute(ActorCaptureFilter.ACTOR_ATTRIBUTE, "operator:alice");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);
    FilterChain noopChain = (req, res) -> {};

    filter.doFilterInternal(request, response, noopChain);

    Map<String, String> mdc = appender.list.get(0).getMDCPropertyMap();
    assertThat(mdc.get("http.method")).isEqualTo("GET");
    assertThat(mdc.get("http.path")).isEqualTo("/api/v1/invoices/rc1-1");
    assertThat(mdc.get("http.status")).isEqualTo("200");
    assertThat(mdc.get("actor")).isEqualTo("operator:alice");
  }

  @Test
  void unauthenticatedRequestIsLoggedWithAnonymousActor() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/records/x/verification");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(404);
    FilterChain noopChain = (req, res) -> {};

    filter.doFilterInternal(request, response, noopChain);

    assertThat(appender.list.get(0).getMDCPropertyMap().get("actor")).isEqualTo("anonymous");
  }
}
