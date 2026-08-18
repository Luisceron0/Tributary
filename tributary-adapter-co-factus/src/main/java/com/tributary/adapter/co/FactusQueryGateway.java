package com.tributary.adapter.co;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tributary.application.port.QueryOutcome;
import com.tributary.application.port.RegimeQueryResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * RF-008's {@code GET /v2/bills?filter[reference_code]=...} — shape confirmed live against the
 * real sandbox this session: {@code {status, message, data: {data: [...], pagination: {...}}}}.
 *
 * <p>A server/network failure maps to {@link QueryOutcome#AMBIGUOUS}, never {@code NOT_FOUND} —
 * RF-008's reconciler must never treat "I couldn't ask" the same as "I asked and there's nothing
 * there." Confusing the two would let the reconciler retry issuance after a network blip that
 * never actually reached Factus, exactly the duplicate-fiscal-document risk ADR-003 exists to
 * prevent.
 *
 * <p><b>{@code externalReference} here is Factus's {@code number} (e.g. {@code
 * "SETP990015225"}), not the CUFE.</b> Confirmed live against the real sandbox this session (T-307):
 * the list endpoint's items carry {@code number}, {@code is_validated}, {@code reference_code} and
 * more, but never {@code cufe} — only the original {@code POST /v2/bills/validate} response does.
 * RF-008's prose says "se adopta el CUFE," which reads as available-on-demand; the real API does
 * not expose it here. {@code number} is what the query endpoint can actually confirm the document
 * by, so it is what gets adopted — the CUFE captured at the original issuance (already persisted
 * via {@code issuance_attempt} at that time) remains the fiscal record's CUFE; reconciliation's
 * job is confirming the document still exists and is validated, not re-fetching a value only the
 * original response carried.
 */
final class FactusQueryGateway {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final FactusRateLimiter rateLimiter;

  FactusQueryGateway(FactusRateLimiter rateLimiter) {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper(), rateLimiter);
  }

  FactusQueryGateway(HttpClient httpClient, ObjectMapper objectMapper, FactusRateLimiter rateLimiter) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
  }

  RegimeQueryResult query(FactusCredentials credentials, FactusToken token, String businessKey) {
    Objects.requireNonNull(credentials, "credentials must not be null");
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(businessKey, "businessKey must not be null");

    String encodedKey = URLEncoder.encode(businessKey, StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(credentials.baseUrl() + "/v2/bills?filter%5Breference_code%5D=" + encodedKey))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token.accessToken())
            .header("Accept", "application/json")
            .GET()
            .build();

    // Audit finding: queries used to bypass the limiter entirely, while the adapter's own javadoc
    // claimed it was "shared across issue/query calls". Factus's quota is per ACCOUNT, and RF-008's
    // reconciler is precisely the component that issues many queries in a sweep — so an unlimited
    // query path could exhaust the quota and block real issuances, which is threat T-010 verbatim.
    rateLimiter.acquire();

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      // Could not even ask — never confused with "asked, and there is nothing there."
      return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    }

    if (response.statusCode() != 200) {
      return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    }

    JsonNode matches;
    try {
      matches = objectMapper.readTree(response.body()).path("data").path("data");
    } catch (IOException e) {
      return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    }

    if (!matches.isArray() || matches.isEmpty()) {
      return new RegimeQueryResult(QueryOutcome.NOT_FOUND, Optional.empty(), List.of());
    }
    if (matches.size() > 1) {
      return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    }

    JsonNode match = matches.get(0);
    boolean isValidated = match.path("is_validated").asBoolean(false);
    // Audit finding: this field used to be dropped on the floor here, while the issuance path read
    // it. Two consequences, both silent — a confirmed rejection reached the operator with no reason
    // attached, and ReconcileInvoiceUseCase.adopt() (which already branches on this list) recorded
    // a document DIAN accepted WITH warnings as a clean ISSUED. RF-002: the warnings are recorded
    // and never discarded, on whichever path observes them.
    List<String> messages = FactusErrorMessages.from(match.get("errors"));

    if (!isValidated) {
      // Audit finding: a bare "not validated" is not a verdict. The list endpoint reports
      // is_validated=false for a bill that is not validated YET as readily as for one DIAN refused,
      // and reconciliation runs exactly when the original response was lost — when a document is
      // most likely still in flight. FOUND_REJECTED is terminal downstream (DocumentState.REJECTED
      // has an empty transition set), so claiming it without the errors that evidence it would make
      // an unreadable state permanent. ADR-003: an ambiguous state never becomes a definitive one.
      if (messages.isEmpty()) {
        return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
      }
      return new RegimeQueryResult(QueryOutcome.FOUND_REJECTED, Optional.empty(), messages);
    }
    // "number", not "cufe" — see class-level note.
    Optional<String> number = match.hasNonNull("number") ? Optional.of(match.get("number").asText()) : Optional.empty();
    return new RegimeQueryResult(QueryOutcome.FOUND_VALIDATED, number, messages);
  }
}
