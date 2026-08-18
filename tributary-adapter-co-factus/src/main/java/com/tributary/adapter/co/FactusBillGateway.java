package com.tributary.adapter.co;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tributary.application.port.IssuanceOutcome;
import com.tributary.application.port.IssuanceResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * T-302 / T-305: {@code POST /v2/bills/validate} — rate-limited (T-301), retried once on {@code
 * 429} after {@code Retry-After} plus jitter (T-302), and mapped onto exactly the four outcomes
 * RF-002 describes (T-305). Response field names confirmed live against the real sandbox this
 * session: {@code status}, {@code data.is_validated}, {@code data.errors} (an object, not an
 * array — key per DIAN rule id), {@code data.cufe}.
 *
 * <p>A {@code 429} is never one of the four outcomes: SRS 5.3 calls it "a failure of Tributary's
 * own limiter, not a normal branch of the issuance flow." One retry is attempted; if that ALSO
 * 429s, the second response is what gets mapped to an outcome (very likely UNREACHABLE-shaped,
 * since a persistent 429 after respecting Retry-After signals something worse than one slow
 * window).
 */
public final class FactusBillGateway {

  private static final int MAX_RATE_LIMIT_RETRIES = 1;
  private static final long JITTER_MILLIS_BOUND = 500;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final FactusRateLimiter rateLimiter;
  private final Random jitterSource;

  public FactusBillGateway(FactusRateLimiter rateLimiter) {
    this(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        new ObjectMapper(),
        rateLimiter,
        new SecureRandom());
  }

  FactusBillGateway(HttpClient httpClient, ObjectMapper objectMapper, FactusRateLimiter rateLimiter, Random jitterSource) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
    this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource must not be null");
  }

  public IssuanceResult validate(FactusCredentials credentials, FactusToken token, JsonNode payload) {
    Objects.requireNonNull(credentials, "credentials must not be null");
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(payload, "payload must not be null");

    HttpResponse<String> response = null;
    for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
      rateLimiter.acquire();
      try {
        response = send(credentials, token, payload);
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        return unreachable(e);
      }

      if (response.statusCode() != 429 || attempt == MAX_RATE_LIMIT_RETRIES) {
        break;
      }
      waitOutRateLimitIncident(response);
    }

    return toIssuanceResult(response);
  }

  private HttpResponse<String> send(FactusCredentials credentials, FactusToken token, JsonNode payload)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(credentials.baseUrl() + "/v2/bills/validate"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token.accessToken())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** T-302: respects Retry-After (delay-seconds form), plus jitter so retries don't all land on the same instant. */
  private void waitOutRateLimitIncident(HttpResponse<String> response) {
    Duration retryAfter = parseRetryAfter(response);
    long jitterMillis = jitterSource.nextLong(JITTER_MILLIS_BOUND + 1);
    Duration wait = retryAfter.plusMillis(jitterMillis);
    try {
      Thread.sleep(wait.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Duration parseRetryAfter(HttpResponse<String> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .map(
            value -> {
              try {
                return Duration.ofSeconds(Long.parseLong(value.trim()));
              } catch (NumberFormatException e) {
                return Duration.ofSeconds(1); // a conservative default if Factus ever sends an HTTP-date form
              }
            })
        .orElse(Duration.ofSeconds(1));
  }

  private IssuanceResult toIssuanceResult(HttpResponse<String> response) {
    if (response.statusCode() == 429) {
      // Both the original attempt and the single retry were rate-limited: something beyond a
      // normal window is wrong, and the system must not keep retrying blindly (ADR-003's own
      // reasoning about irreversibility applied to a different failure mode).
      return new IssuanceResult(
          IssuanceOutcome.UNREACHABLE, Optional.empty(), List.of(), "HTTP 429 after exhausting retries");
    }

    // Only a 2xx response carries a DIAN verdict. Anything else — 401 from an expired token, 5xx
    // from Factus, 502 from something in front of it — is a TRANSPORT failure, and mapping it to
    // REJECTED would be irreversible: DocumentState.REJECTED has an empty transition set, so an
    // invoice DIAN never even saw would be permanently recorded as one DIAN refused. ADR-003's
    // whole point is that an ambiguous failure must never become a definitive verdict; UNREACHABLE
    // routes to NEEDS_RECONCILIATION, which forces a query before any retry.
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      return new IssuanceResult(
          IssuanceOutcome.UNREACHABLE,
          Optional.empty(),
          List.of(),
          "HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode json;
    try {
      json = objectMapper.readTree(response.body());
    } catch (IOException e) {
      return new IssuanceResult(
          IssuanceOutcome.UNREACHABLE, Optional.empty(), List.of(), "unparseable response body: " + e.getMessage());
    }

    JsonNode data = json.get("data");
    if (data == null) {
      return new IssuanceResult(
          IssuanceOutcome.UNREACHABLE, Optional.empty(), List.of(), "response missing \"data\": " + response.body());
    }

    boolean isValidated = data.path("is_validated").asBoolean(false);
    List<String> messages = FactusErrorMessages.from(data.get("errors"));
    Optional<String> cufe = data.hasNonNull("cufe") ? Optional.of(data.get("cufe").asText()) : Optional.empty();

    IssuanceOutcome outcome;
    if (!isValidated) {
      outcome = IssuanceOutcome.REJECTED;
    } else if (messages.isEmpty()) {
      outcome = IssuanceOutcome.ACCEPTED;
    } else {
      outcome = IssuanceOutcome.ACCEPTED_WITH_WARNINGS;
    }

    return new IssuanceResult(outcome, isValidated ? cufe : Optional.empty(), messages, response.body());
  }

  private IssuanceResult unreachable(Exception cause) {
    return new IssuanceResult(
        IssuanceOutcome.UNREACHABLE, Optional.empty(), List.of(), "network failure: " + cause.getMessage());
  }
}
